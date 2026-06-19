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
                System.out.println(input + ": command not found");
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
                break;
            default:
                System.out.println(args + ": not found");
        }
    }
}
