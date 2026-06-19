import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;

public class Main {
    public static void main(String[] args) throws Exception {

        Scanner scanner = new Scanner(System.in);
        while (true) {
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

    private static void executeCommand(String input) {
        List<String> parsedArgs = parseArguments(input);
        if (parsedArgs.isEmpty()) return;

        String command = parsedArgs.get(0);
        List<String> args = parsedArgs.subList(1, parsedArgs.size());

        String redirectOutFile = null;
        String redirectErrFile = null;
        boolean appendOut = false;
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
                System.setErr(new PrintStream(new FileOutputStream(f)));
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
                default:
                    String executablePath = getExecutablePath(command);
                    if (executablePath != null) {
                        executeExternalProgram(command, args, redirectOutFile, redirectErrFile, appendOut);
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

    private static void executeExternalProgram(String command, List<String> args, String redirectOutFile, String redirectErrFile, boolean appendOut) {
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
                pb.redirectError(new File(redirectErrFile));
            } else {
                pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            }
            
            pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
            
            Process process = pb.start();
            process.waitFor();
        } catch (Exception e) {
            System.err.println("Error executing program: " + e.getMessage());
        }
    }
}
