import java.io.File;
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
    }

    private static void executeCommand(String input) {
        String[] parts = input.split(" ", 2);
        String command = parts[0];
        String args = parts.length > 1 ? parts[1] : "";

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
            default:
                String executablePath = getExecutablePath(command);
                if (executablePath != null) {
                    executeExternalProgram(executablePath, args);
                } else {
                    System.out.println(input + ": command not found");
                }
        }
    }

    private static void executeExit(String args) {
        int exitCode = 0;
        if (!args.isEmpty()) {
            try {
                exitCode = Integer.parseInt(args.trim());
            } catch (NumberFormatException e) {
                // Ignore and use default 0
            }
        }
        System.exit(exitCode);
    }

    private static void executeEcho(String args) {
        System.out.println(args);
    }

    private static void executeType(String args) {
        switch (args) {
            case "echo":
            case "exit":
            case "type":
                System.out.println(args + " is a shell builtin");
                return;
        }
        String executablePath = getExecutablePath(args);
        if (executablePath != null) {
            System.out.println(args + " is " + executablePath);
            return;
        }

        System.out.println(args + ": not found");
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

    private static void executeExternalProgram(String executablePath, String argsStr) {
        try {
            List<String> commandList = new ArrayList<>();
            commandList.add(executablePath);
            if (!argsStr.trim().isEmpty()) {
                commandList.addAll(Arrays.asList(argsStr.trim().split(" +")));
            }
            ProcessBuilder pb = new ProcessBuilder(commandList);
            pb.inheritIO();
            Process process = pb.start();
            process.waitFor();
        } catch (Exception e) {
            System.out.println("Error executing program: " + e.getMessage());
        }
    }
}
