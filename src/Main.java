import util.Metrics;

public class Main {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("Usage: pre_process | run_query <start> <end> <buffer_size> [--index] [--metrics]");
            System.exit(1);
        }

        switch (args[0]) {
            case "pre_process" -> PreProcessor.run();
            case "run_query" -> {
                if (args.length < 4) {
                    System.err.println("Usage: run_query <start_range> <end_range> <buffer_size>");
                    System.err.println("  start_range  lower bound title (inclusive), up to 30 chars");
                    System.err.println("  end_range    upper bound title (inclusive), up to 30 chars");
                    System.err.println("  buffer_size  number of buffer frames (positive integer)");
                    System.exit(1);
                }
                String start = args[1];
                String end = args[2];
                if (start.length() > 30) {
                    System.err.println("Error: start_range exceeds max title length of 30 characters");
                    System.exit(1);
                }
                if (end.length() > 30) {
                    System.err.println("Error: end_range exceeds max title length of 30 characters");
                    System.exit(1);
                }
                int bufferSize;
                try {
                    bufferSize = Integer.parseInt(args[3]);
                } catch (NumberFormatException e) {
                    System.err.println("Error: buffer_size must be a positive integer, got: " + args[3]);
                    System.exit(1);
                    return;
                }
                if (bufferSize < 3) {
                    System.err.println("Error: buffer_size must be at least 3 to run BNL join");
                    System.exit(1);
                }
                // Optional flags after the positional args, in any order:
                // --index uses the B+ tree access method; --metrics prints timing/memory.
                boolean useIndex = false;
                boolean metrics = false;
                for (int i = 4; i < args.length; i++) {
                    switch (args[i]) {
                        case "--index" -> useIndex = true;
                        case "--metrics" -> metrics = true;
                        default -> {
                            System.err.println("Unknown run_query option: " + args[i]);
                            System.exit(1);
                            return;
                        }
                    }
                }

                // Time only the query execution, excluding arg parsing and startup.
                long startNanos = System.nanoTime();
                long resultCount = RunQuery.run(start, end, bufferSize, useIndex);
                long elapsedNanos = System.nanoTime() - startNanos;
                if (metrics) {
                    Metrics.report(elapsedNanos, resultCount);
                }
            }
            default -> {
                System.err.println("Unknown command: " + args[0]);
                System.exit(1);
            }
        }
    }
}
