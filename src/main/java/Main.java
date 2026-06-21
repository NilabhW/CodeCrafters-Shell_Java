import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;

public class Main {
    static class Job {
        int id;
        long pid;
        Process process;
        String command;
        String status;

        public Job(int id, Process process, String command, String status) {
            this.id = id;
            this.pid = process.pid();
            this.process = process;
            this.command = command;
            this.status = status;
        }
    }

    static class PipelineCommand {
        String command;
        List<String> args;
        boolean isBuiltin;
        String redirectOutFile;
        String redirectErrFile;
        boolean appendOut;
        boolean appendErr;
    }

    private static boolean isBuiltin(String command) {
        return Arrays.asList("echo", "exit", "type", "pwd", "cd", "jobs").contains(command);
    }

    static List<Job> backgroundJobs = new ArrayList<>();

    public static void main(String[] args) throws Exception {

        Scanner scanner = new Scanner(System.in);
        while (true) {
            printAndReapJobs(true);
            System.out.print("$ ");
            if (!scanner.hasNextLine())
                break;
            String input = scanner.nextLine();
            if (input.trim().isEmpty()) {
                continue;
            }

            executeCommand(input);
        }
        scanner.close();
    }

    private static void printAndReapJobs(boolean onlyPrintDone) {
        List<Job> toRemove = new ArrayList<>();
        for (int i = 0; i < backgroundJobs.size(); i++) {
            Job job = backgroundJobs.get(i);
            char marker = ' ';
            if (i == backgroundJobs.size() - 1) {
                marker = '+';
            } else if (i == backgroundJobs.size() - 2) {
                marker = '-';
            }

            boolean isDone = false;
            if (!job.process.isAlive()) {
                job.status = "Done";
                if (job.command.endsWith("&")) {
                    job.command = job.command.substring(0, job.command.lastIndexOf('&')).trim();
                }
                toRemove.add(job);
                isDone = true;
            }

            if (!onlyPrintDone || isDone) {
                String statusPadded = String.format("%-24s", job.status);
                System.out.printf("[%d]%c  %s%s\n", job.id, marker, statusPadded, job.command);
            }
        }
        backgroundJobs.removeAll(toRemove);
    }

    private static void executeCommand(String input) {
        List<String> pipelines = splitPipelines(input);
        if (pipelines.size() > 1) {
            executePipeline(pipelines, input);
            return;
        }

        List<String> parsedArgs = parseArguments(input);
        if (parsedArgs.isEmpty()) return;

        boolean runInBackground = false;
        if (parsedArgs.get(parsedArgs.size() - 1).equals("&")) {
            runInBackground = true;
            parsedArgs.remove(parsedArgs.size() - 1);
        }

        if (parsedArgs.isEmpty()) return;

        String command = parsedArgs.get(0);
        List<String> args = parsedArgs.subList(1, parsedArgs.size());

        String redirectOutFile = null;
        String redirectErrFile = null;
        boolean appendOut = false;
        boolean appendErr = false;
        for (int i = args.size() - 2; i >= 0; i--) {
            String arg = args.get(i);
            if (arg.equals(">") || arg.equals("1>")) {
                redirectOutFile = args.get(i + 1);
                appendOut = false;
                args = new ArrayList<>(args.subList(0, i));
            } else if (arg.equals(">>") || arg.equals("1>>")) {
                redirectOutFile = args.get(i + 1);
                appendOut = true;
                args = new ArrayList<>(args.subList(0, i));
            } else if (arg.equals("2>")) {
                redirectErrFile = args.get(i + 1);
                appendErr = false;
                args = new ArrayList<>(args.subList(0, i));
            } else if (arg.equals("2>>")) {
                redirectErrFile = args.get(i + 1);
                appendErr = true;
                args = new ArrayList<>(args.subList(0, i));
            }
        }

        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        
        if (redirectOutFile != null) {
            try {
                File f = new File(redirectOutFile);
                if (f.getParentFile() != null) f.getParentFile().mkdirs();
                System.setOut(new PrintStream(new FileOutputStream(f, appendOut)));
            } catch (Exception e) {
                System.out.println("Error setting up redirection: " + e.getMessage());
                return;
            }
        }

        if (redirectErrFile != null) {
            try {
                File f = new File(redirectErrFile);
                if (f.getParentFile() != null) f.getParentFile().mkdirs();
                System.setErr(new PrintStream(new FileOutputStream(f, appendErr)));
            } catch (Exception e) {
                System.out.println("Error setting up error redirection: " + e.getMessage());
                return;
            }
        }

        try {
            switch (command) {
                case "exit":
                    executeExit(args);
                    break;
                case "echo":
                    executeEcho(args);
                    break;
                case "type":
                    executeType(args);
                    break;
                case "pwd":
                    executePwd(args);
                    break;
                case "cd":
                    executeCd(args);
                    break;
                case "jobs":
                    printAndReapJobs(false);
                    break;
                default:
                    String executablePath = getExecutablePath(command);
                    if (executablePath != null) {
                        executeExternalProgram(command, args, redirectOutFile, redirectErrFile, appendOut, appendErr, runInBackground, input);
                    } else {
                        System.out.println(command + ": command not found");
                    }
            }
        } finally {
            if (redirectOutFile != null) {
                System.out.flush();
                System.setOut(originalOut);
            }
            if (redirectErrFile != null) {
                System.err.flush();
                System.setErr(originalErr);
            }
        }
    }

    private static List<String> splitPipelines(String input) {
        List<String> commands = new ArrayList<>();
        StringBuilder currentCommand = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (inSingleQuote) {
                if (c == '\'') {
                    inSingleQuote = false;
                }
                currentCommand.append(c);
            } else if (inDoubleQuote) {
                if (c == '\\' && i + 1 < input.length()) {
                    char nextChar = input.charAt(i + 1);
                    if (nextChar == '\\' || nextChar == '"' || nextChar == '$' || nextChar == '`' || nextChar == '\n') {
                        currentCommand.append(c);
                        currentCommand.append(nextChar);
                        i++;
                    } else {
                        currentCommand.append(c);
                    }
                } else if (c == '\"') {
                    inDoubleQuote = false;
                    currentCommand.append(c);
                } else {
                    currentCommand.append(c);
                }
            } else {
                if (c == '\\' && i + 1 < input.length()) {
                    currentCommand.append(c);
                    currentCommand.append(input.charAt(i + 1));
                    i++;
                } else if (c == '\'') {
                    inSingleQuote = true;
                    currentCommand.append(c);
                } else if (c == '\"') {
                    inDoubleQuote = true;
                    currentCommand.append(c);
                } else if (c == '|') {
                    commands.add(currentCommand.toString());
                    currentCommand.setLength(0);
                } else {
                    currentCommand.append(c);
                }
            }
        }
        commands.add(currentCommand.toString());
        return commands;
    }

    private static void executePipeline(List<String> pipelines, String originalInput) {
        boolean runInBackground = false;
        String lastPipelineStr = pipelines.get(pipelines.size() - 1);
        List<String> lastArgs = parseArguments(lastPipelineStr);
        if (!lastArgs.isEmpty() && lastArgs.get(lastArgs.size() - 1).equals("&")) {
            runInBackground = true;
        }

        List<PipelineCommand> cmds = new ArrayList<>();
        for (int pIndex = 0; pIndex < pipelines.size(); pIndex++) {
            String pStr = pipelines.get(pIndex);
            List<String> args = parseArguments(pStr);
            
            if (pIndex == pipelines.size() - 1 && runInBackground) {
                if (!args.isEmpty() && args.get(args.size() - 1).equals("&")) {
                    args.remove(args.size() - 1);
                }
            }
            if (args.isEmpty()) continue;

            PipelineCommand cmd = new PipelineCommand();
            cmd.command = args.get(0);
            args = args.subList(1, args.size());
            
            for (int i = args.size() - 2; i >= 0; i--) {
                String arg = args.get(i);
                if (arg.equals(">") || arg.equals("1>")) {
                    cmd.redirectOutFile = args.get(i + 1);
                    cmd.appendOut = false;
                    args = new ArrayList<>(args.subList(0, i));
                } else if (arg.equals(">>") || arg.equals("1>>")) {
                    cmd.redirectOutFile = args.get(i + 1);
                    cmd.appendOut = true;
                    args = new ArrayList<>(args.subList(0, i));
                } else if (arg.equals("2>")) {
                    cmd.redirectErrFile = args.get(i + 1);
                    cmd.appendErr = false;
                    args = new ArrayList<>(args.subList(0, i));
                } else if (arg.equals("2>>")) {
                    cmd.redirectErrFile = args.get(i + 1);
                    cmd.appendErr = true;
                    args = new ArrayList<>(args.subList(0, i));
                }
            }
            cmd.args = args;
            cmd.isBuiltin = isBuiltin(cmd.command);
            if (!cmd.isBuiltin && getExecutablePath(cmd.command) == null) {
                System.out.println(cmd.command + ": command not found");
                return;
            }
            cmds.add(cmd);
        }

        if (cmds.isEmpty()) return;

        byte[] currentInput = null;
        List<Process> allProcesses = new ArrayList<>();

        for (int i = 0; i < cmds.size(); i++) {
            PipelineCommand cmd = cmds.get(i);
            
            if (cmd.isBuiltin) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                PrintStream ps = new PrintStream(baos);
                PrintStream oldOut = System.out;
                PrintStream oldErr = System.err;
                
                System.setOut(ps);
                if (cmd.redirectErrFile != null) {
                    try {
                        File f = new File(cmd.redirectErrFile);
                        if (f.getParentFile() != null) f.getParentFile().mkdirs();
                        System.setErr(new PrintStream(new FileOutputStream(f, cmd.appendErr)));
                    } catch (Exception e) {
                        System.setErr(oldErr);
                    }
                }
                
                try {
                    switch (cmd.command) {
                        case "exit": executeExit(cmd.args); break;
                        case "echo": executeEcho(cmd.args); break;
                        case "type": executeType(cmd.args); break;
                        case "pwd": executePwd(cmd.args); break;
                        case "cd": executeCd(cmd.args); break;
                        case "jobs": printAndReapJobs(false); break;
                    }
                } finally {
                    System.out.flush();
                    System.setOut(oldOut);
                    if (System.err != oldErr) {
                        System.err.close();
                        System.setErr(oldErr);
                    }
                }
                
                currentInput = baos.toByteArray();
                
                if (i == cmds.size() - 1) {
                    if (cmd.redirectOutFile != null) {
                        try (FileOutputStream fos = new FileOutputStream(cmd.redirectOutFile, cmd.appendOut)) {
                            fos.write(currentInput);
                        } catch (Exception e) {
                            System.err.println("Error writing to file: " + e.getMessage());
                        }
                    } else {
                        try {
                            System.out.write(currentInput);
                        } catch (Exception e) {}
                    }
                }
            } else {
                int endIdx = i;
                while (endIdx < cmds.size() && !cmds.get(endIdx).isBuiltin) {
                    endIdx++;
                }
                List<ProcessBuilder> builders = new ArrayList<>();
                for (int j = i; j < endIdx; j++) {
                    PipelineCommand c = cmds.get(j);
                    List<String> commandList = new ArrayList<>();
                    commandList.add(c.command);
                    commandList.addAll(c.args);
                    ProcessBuilder pb = new ProcessBuilder(commandList);
                    pb.directory(new File(System.getProperty("user.dir")));
                    
                    if (j == endIdx - 1) {
                        if (endIdx < cmds.size()) {
                            pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
                        } else {
                            if (c.redirectOutFile != null) {
                                pb.redirectOutput(c.appendOut ? ProcessBuilder.Redirect.appendTo(new File(c.redirectOutFile)) : ProcessBuilder.Redirect.to(new File(c.redirectOutFile)));
                            } else {
                                pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                            }
                        }
                    }
                    
                    if (c.redirectErrFile != null) {
                        pb.redirectError(c.appendErr ? ProcessBuilder.Redirect.appendTo(new File(c.redirectErrFile)) : ProcessBuilder.Redirect.to(new File(c.redirectErrFile)));
                    } else {
                        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                    }
                    
                    if (j == i) {
                        if (currentInput != null) {
                            pb.redirectInput(ProcessBuilder.Redirect.PIPE);
                        } else {
                            pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
                        }
                    }
                    
                    builders.add(pb);
                }
                
                try {
                    List<Process> processes = ProcessBuilder.startPipeline(builders);
                    allProcesses.addAll(processes);
                    
                    if (currentInput != null) {
                        Process first = processes.get(0);
                        try (OutputStream os = first.getOutputStream()) {
                            os.write(currentInput);
                        }
                        currentInput = null;
                    }
                    
                    if (endIdx < cmds.size()) {
                        Process last = processes.get(processes.size() - 1);
                        last.getInputStream().close();
                    }
                    
                } catch (Exception e) {
                    System.err.println("Error executing pipeline block: " + e.getMessage());
                }
                
                i = endIdx - 1;
            }
        }

        if (runInBackground && !allProcesses.isEmpty()) {
            Process lastProcess = allProcesses.get(allProcesses.size() - 1);
            int jobId = 1;
            for (Job job : backgroundJobs) {
                if (job.id >= jobId) {
                    jobId = job.id + 1;
                }
            }
            System.out.println("[" + jobId + "] " + lastProcess.pid());
            backgroundJobs.add(new Job(jobId, lastProcess, originalInput.trim(), "Running"));
        } else {
            for (Process p : allProcesses) {
                try {
                    p.waitFor();
                } catch (InterruptedException e) {}
            }
        }
    }

    private static List<String> parseArguments(String input) {
        List<String> args = new ArrayList<>();
        StringBuilder currentArg = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean inWord = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (inSingleQuote) {
                if (c == '\'') {
                    inSingleQuote = false;
                } else {
                    currentArg.append(c);
                }
            } else if (inDoubleQuote) {
                if (c == '\\') {
                    if (i + 1 < input.length()) {
                        char nextChar = input.charAt(i + 1);
                        if (nextChar == '\\' || nextChar == '"' || nextChar == '$' || nextChar == '`' || nextChar == '\n') {
                            currentArg.append(nextChar);
                            i++;
                        } else {
                            currentArg.append(c);
                        }
                    } else {
                        currentArg.append(c);
                    }
                } else if (c == '\"') {
                    inDoubleQuote = false;
                } else {
                    currentArg.append(c);
                }
            } else {
                if (c == '\\') {
                    if (i + 1 < input.length()) {
                        currentArg.append(input.charAt(i + 1));
                        i++;
                        inWord = true;
                    }
                } else if (c == '\'') {
                    inSingleQuote = true;
                    inWord = true;
                } else if (c == '\"') {
                    inDoubleQuote = true;
                    inWord = true;
                } else if (c == ' ' || c == '\t') {
                    if (inWord) {
                        args.add(currentArg.toString());
                        currentArg.setLength(0);
                        inWord = false;
                    }
                } else {
                    currentArg.append(c);
                    inWord = true;
                }
            }
        }

        if (inWord) {
            args.add(currentArg.toString());
        }

        return args;
    }

    private static void executeExit(List<String> args) {
        int exitCode = 0;
        if (!args.isEmpty()) {
            try {
                exitCode = Integer.parseInt(args.get(0));
            } catch (NumberFormatException e) {
                // Ignore and use default 0
            }
        }
        System.exit(exitCode);
    }

    private static void executeEcho(List<String> args) {
        System.out.println(String.join(" ", args));
    }

    private static void executePwd(List<String> args) {
        System.out.println(System.getProperty("user.dir"));
    }

    private static void executeCd(List<String> args) {
        if (args.isEmpty()) return;
        String dirArg = args.get(0);
        if (dirArg.startsWith("~")) {
            String home = System.getenv("HOME");
            if (home != null) {
                dirArg = home + dirArg.substring(1);
            }
        }

        String currentDir = System.getProperty("user.dir");
        Path pathArg = Paths.get(dirArg);
        Path newPath = pathArg.isAbsolute() ? pathArg.normalize() : Paths.get(currentDir, dirArg).normalize();
        
        if (Files.isDirectory(newPath)) {
            System.setProperty("user.dir", newPath.toString());
        } else {
            System.out.println("cd: " + args.get(0) + ": No such file or directory");
        }
    }

    private static void executeType(List<String> args) {
        if (args.isEmpty()) return;
        String typeArg = args.get(0);
        switch (typeArg) {
            case "echo":
            case "exit":
            case "type":
            case "pwd":
            case "cd":
            case "jobs":
                System.out.println(typeArg + " is a shell builtin");
                return;
        }
        String executablePath = getExecutablePath(typeArg);
        if (executablePath != null) {
            System.out.println(typeArg + " is " + executablePath);
            return;
        }

        System.out.println(typeArg + ": not found");
    }

    private static String getExecutablePath(String command) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String p : pathEnv.split(File.pathSeparator)) {
                File file = new File(p, command);
                if (file.isFile() && file.canExecute()) {
                    return file.getAbsolutePath();
                }
            }
        }
        return null;
    }

    private static void executeExternalProgram(String command, List<String> args, String redirectOutFile, String redirectErrFile, boolean appendOut, boolean appendErr, boolean runInBackground, String originalInput) {
        try {
            List<String> commandList = new ArrayList<>();
            commandList.add(command);
            commandList.addAll(args);
            ProcessBuilder pb = new ProcessBuilder(commandList);
            pb.directory(new File(System.getProperty("user.dir")));
            
            if (redirectOutFile != null) {
                if (appendOut) {
                    pb.redirectOutput(ProcessBuilder.Redirect.appendTo(new File(redirectOutFile)));
                } else {
                    pb.redirectOutput(new File(redirectOutFile));
                }
            } else {
                pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            }
            
            if (redirectErrFile != null) {
                if (appendErr) {
                    pb.redirectError(ProcessBuilder.Redirect.appendTo(new File(redirectErrFile)));
                } else {
                    pb.redirectError(new File(redirectErrFile));
                }
            } else {
                pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            }
            
            pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
            
            Process process = pb.start();
            if (runInBackground) {
                int jobId = 1;
                for (Job job : backgroundJobs) {
                    if (job.id >= jobId) {
                        jobId = job.id + 1;
                    }
                }
                System.out.println("[" + jobId + "] " + process.pid());
                backgroundJobs.add(new Job(jobId, process, originalInput.trim(), "Running"));
            } else {
                process.waitFor();
            }
        } catch (Exception e) {
            System.err.println("Error executing program: " + e.getMessage());
        }
    }
}
