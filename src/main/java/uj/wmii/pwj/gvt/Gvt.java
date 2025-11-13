package uj.wmii.pwj.gvt;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

public class Gvt {

    private final ExitHandler exitHandler;
    private final RepositoryManager repository;

    public Gvt(ExitHandler exitHandler) {
        this.exitHandler = exitHandler;
        this.repository = new RepositoryManager();
    }

    public static void main(String... args) {
        Gvt gvt = new Gvt(new ExitHandler());
        gvt.mainInternal(args);
    }

    public void mainInternal(String... args) {
        if (args.length == 0) {
            exitHandler.exit(1, "Please specify command.");
            return;
        }

        String action = args[0];
        String[] params = Arrays.copyOfRange(args, 1, args.length);

        if (!action.equals("init") && !repository.isInitialized()) {
            exitHandler.exit(-2, "Current directory is not initialized. Please use init command to initialize.");
            return;
        }

        try {
            executeAction(action, params);
        } catch (RepositoryException e) {
            exitHandler.exit(e.getErrorCode(), e.getMessage());
        } catch (IOException e) {
            exitHandler.exit(-3, "Underlying system problem. See ERR for details.");
        }
    }

    private void executeAction(String action, String[] params) throws IOException {
        switch (action) {
            case "init" -> initialize();
            case "add" -> addFile(params);
            case "detach" -> detachFile(params);
            case "commit" -> commitChanges(params);
            case "checkout" -> checkoutVersion(params);
            case "history" -> displayHistory(params);
            case "version" -> showVersion(params);
            default -> exitHandler.exit(1, "Unknown command " + action + ".");
        }
    }

    private void initialize() throws IOException {
        repository.initialize();
        exitHandler.exit(0, "Current directory initialized successfully.");
    }

    private void addFile(String[] params) throws IOException {
        CommandInput input = CommandInput.parse(params);
        if (input.files().isEmpty()) {
            exitHandler.exit(20, "Please specify file to add.");
            return;
        }

        String filename = input.files().get(0);
        repository.addFile(filename, input.message());
        exitHandler.exit(0, "File added successfully. File: " + filename);
    }

    private void detachFile(String[] params) throws IOException {
        CommandInput input = CommandInput.parse(params);
        if (input.files().isEmpty()) {
            exitHandler.exit(30, "Please specify file to detach.");
            return;
        }

        String filename = input.files().get(0);
        repository.detachFile(filename, input.message());
        exitHandler.exit(0, "File detached successfully. File: " + filename);
    }

    private void commitChanges(String[] params) throws IOException {
        CommandInput input = CommandInput.parse(params);
        if (input.files().isEmpty()) {
            exitHandler.exit(50, "Please specify file to commit.");
            return;
        }

        String filename = input.files().get(0);
        repository.commitFile(filename, input.message());
        exitHandler.exit(0, "File committed successfully. File: " + filename);
    }

    private void checkoutVersion(String[] params) throws IOException {
        if (params.length == 0) {
            exitHandler.exit(60, "Invalid version number: ");
            return;
        }

        String version = params[0];
        repository.checkout(version);
        exitHandler.exit(0, "Checkout successful for version: " + version);
    }

    private void showVersion(String[] params) throws IOException {
        String version = params.length > 0 ? params[0] : repository.getCurrentVersion();
        VersionInfo info = repository.getVersionInfo(version);
        exitHandler.exit(0, "Version: " + info.version() + "\n" + info.message());
    }

    private void displayHistory(String[] params) throws IOException {
        int limit = parseHistoryLimit(params);
        String history = repository.getHistory(limit);
        exitHandler.exit(0, history);
    }

    private int parseHistoryLimit(String[] params) {
        if (params.length == 2 && "-last".equals(params[0])) {
            try {
                int limit = Integer.parseInt(params[1]);
                return limit > 0 ? limit : -1;
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }
}

record CommandInput(List<String> files, String message) {
    public static CommandInput parse(String[] args) {
        List<String> files = new ArrayList<>();
        String message = null;

        if (args.length >= 2 && "-m".equals(args[args.length - 2])) {
            files.add(args[0]);
            message = args[args.length - 1];
        } else if (args.length >= 1) {
            files.add(args[0]);
        }

        return new CommandInput(files, message);
    }
}

record VersionInfo(String version, String message) {
}

class RepositoryManager {
    private static final Path REPO_BASE = Paths.get(".gvt");
    private static final Path VERSIONS = REPO_BASE.resolve("versions");
    private static final Path CURRENT_VERSION_FILE = REPO_BASE.resolve("current");
    private static final Path HISTORY_LOG = REPO_BASE.resolve("history.log");

    public RepositoryManager() {
    }

    public boolean isInitialized() {
        return Files.isDirectory(REPO_BASE);
    }

    public void initialize() throws IOException {
        if (isInitialized()) {
            throw new RepositoryException(10, "Current directory is already initialized.");
        }

        Files.createDirectories(VERSIONS.resolve("0"));
        writeFile(CURRENT_VERSION_FILE, "0");
        logVersion(0, "GVT initialized.");
    }

    public void addFile(String filename, String userMessage) throws IOException {
        Path file = Paths.get(filename);
        validateFileExists(file);

        long currentVersion = getCurrentVersionNumber();
        Path currentDir = getVersionDirectory(currentVersion);

        if (Files.exists(currentDir.resolve(filename))) {
            throw new RepositoryException(0, "File already added. File: " + filename);
        }

        createNewVersion(currentVersion, currentDir, userMessage, "File added successfully. File: " + filename);

        Path newVersionDir = getVersionDirectory(currentVersion + 1);
        Files.copy(file, newVersionDir.resolve(filename), StandardCopyOption.REPLACE_EXISTING);
    }

    public void detachFile(String filename, String userMessage) throws IOException {
        long currentVersion = getCurrentVersionNumber();
        Path currentDir = getVersionDirectory(currentVersion);

        if (!Files.exists(currentDir.resolve(filename))) {
            throw new RepositoryException(0, "File is not added to gvt. File: " + filename);
        }

        createNewVersion(currentVersion, currentDir, userMessage, "File detached successfully. File: " + filename);

        Path newVersionDir = getVersionDirectory(currentVersion + 1);
        Files.deleteIfExists(newVersionDir.resolve(filename));
    }

    public void commitFile(String filename, String userMessage) throws IOException {
        Path file = Paths.get(filename);
        validateFileExistsToCommit(file);

        long currentVersion = getCurrentVersionNumber();
        Path currentDir = getVersionDirectory(currentVersion);

        if (!Files.exists(currentDir.resolve(filename))) {
            throw new RepositoryException(0, "File is not added to gvt. File: " + filename);
        }

        createNewVersion(currentVersion, currentDir, userMessage, "File committed successfully. File: " + filename);

        Path newVersionDir = getVersionDirectory(currentVersion + 1);
        Files.copy(file, newVersionDir.resolve(filename), StandardCopyOption.REPLACE_EXISTING);
    }

    public void checkout(String version) throws IOException {
        if (!isValidVersion(version)) {
            throw new RepositoryException(60, "Invalid version number: " + version);
        }

        long currentVersion = getCurrentVersionNumber();
        Path currentDir = getVersionDirectory(currentVersion);
        Path targetDir = getVersionDirectory(version);

        try (Stream<Path> files = Files.list(currentDir)) {
            files.map(Path::getFileName).filter(Objects::nonNull).forEach(this::deleteFileInWorkingDir);
        }

        try (Stream<Path> files = Files.list(targetDir)) {
            files.forEach(source -> copyToWorkingDir(source, targetDir));
        }

        writeFile(CURRENT_VERSION_FILE, version);
    }

    public String getCurrentVersion() throws IOException {
        return readFile(CURRENT_VERSION_FILE).trim();
    }

    public VersionInfo getVersionInfo(String version) throws IOException {
        if (!isValidVersion(version)) {
            throw new RepositoryException(60, "Invalid version number: " + version);
        }

        String logEntry = findLogEntry(version);
        String message = logEntry.substring(logEntry.indexOf(":") + 2).replace("\\n", "\n");

        return new VersionInfo(version, message);
    }

    public String getHistory(int limit) throws IOException {
        if (!Files.exists(HISTORY_LOG)) {
            return "No history available.";
        }

        List<String> entries = Files.readAllLines(HISTORY_LOG);
        if (entries.isEmpty()) {
            return "No history available.";
        }

        List<String> result = new ArrayList<>();
        int start = limit > 0 ? Math.max(0, entries.size() - limit) : 0;

        for (int i = entries.size() - 1; i >= start; i--) {
            String entry = entries.get(i);
            String formatted = formatHistoryEntry(entry);
            result.add(formatted);
        }

        return String.join("", result);
    }

    private long getCurrentVersionNumber() throws IOException {
        return Long.parseLong(getCurrentVersion());
    }

    private Path getVersionDirectory(long version) {
        return VERSIONS.resolve(String.valueOf(version));
    }

    private Path getVersionDirectory(String version) {
        return VERSIONS.resolve(version);
    }

    private boolean isValidVersion(String version) {
        try {
            long v = Long.parseLong(version);
            return v >= 0 && Files.isDirectory(getVersionDirectory(version));
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void validateFileExists(Path file) {
        if (!Files.exists(file) || Files.isDirectory(file)) {
            throw new RepositoryException(21, "File not found. File: " + file.getFileName());
        }
    }

    private void validateFileExistsToCommit(Path file) {
        if (!Files.exists(file) || Files.isDirectory(file)) {
            throw new RepositoryException(51, "File not found. File: " + file.getFileName());
        }
    }

    private void createNewVersion(long baseVersion, Path baseDir, String userMessage, String defaultMessage) throws IOException {
        long newVersion = baseVersion + 1;
        Path newDir = getVersionDirectory(newVersion);

        Files.createDirectories(newDir);
        copyDirectoryContents(baseDir, newDir);

        writeFile(CURRENT_VERSION_FILE, String.valueOf(newVersion));

        String message = userMessage != null ? userMessage.trim() : defaultMessage.trim();
        logVersion(newVersion, message);
    }

    private void copyDirectoryContents(Path source, Path target) throws IOException {
        try (Stream<Path> files = Files.list(source)) {
            files.forEach(file -> {
                try {
                    Files.copy(file, target.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    private void logVersion(long version, String message) throws IOException {
        String entry = version + ": " + message.replace("\n", "\\n") + "\n";
        Files.writeString(HISTORY_LOG, entry, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private String readFile(Path path) throws IOException {
        return Files.readString(path);
    }

    private void writeFile(Path path, String content) throws IOException {
        Files.writeString(path, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private String findLogEntry(String version) throws IOException {
        try (Stream<String> lines = Files.lines(HISTORY_LOG)) {
            return lines.filter(line -> line.startsWith(version + ":")).findFirst().orElseThrow(() -> new RepositoryException(60, "Invalid version number: " + version));
        }
    }

    private String formatHistoryEntry(String entry) {
        int colonIndex = entry.indexOf(":");
        if (colonIndex == -1) return entry + "\n";

        String versionPart = entry.substring(0, colonIndex + 2);
        String message = entry.substring(colonIndex + 2).replace("\\n", "\n").split("\n")[0];
        return versionPart + message + "\n";
    }

    private void deleteFileInWorkingDir(Path filename) {
        try {
            Files.deleteIfExists(Paths.get(filename.toString()));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void copyToWorkingDir(Path source, Path baseDir) {
        try {
            Path relative = baseDir.relativize(source);
            if (relative.toString().equals(".gvt")) return;

            Path target = Paths.get(relative.toString());
            if (Files.isDirectory(source)) {
                Files.createDirectories(target);
            } else {
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}

class RepositoryException extends RuntimeException {
    private final int errorCode;

    public RepositoryException(int errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public int getErrorCode() {
        return errorCode;
    }
}