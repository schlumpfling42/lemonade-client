package net.rka.ai.tools;

import dev.langchain4j.invocation.InvocationParameters;
import net.rka.ai.config.Settings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A conversation's trusted working folder. File tools may only touch paths inside it; the
 * terminal starts there. The folder reaches tools as the {@link #PARAMETER} invocation parameter,
 * set per run by the agent; the default is {@code WORK_FOLDER}, else the user's home folder.
 *
 * <h2>How the folder gets to a tool</h2>
 * <pre>
 *   Agent.run(settings) → graph state "workFolder" → ToolCallRunner.run(calls, folder)
 *     → ToolRegistry.execute(call, InvocationParameters{workFolder=folder})
 *       → LangChain4j calls e.g. FileTools.readFile(path, parameters) → WorkFolder.of(parameters)
 * </pre>
 * Tool objects are created once and shared by all conversations, so the folder can't be a field;
 * LangChain4j's {@link InvocationParameters} is its way of passing per-call context. Parameters of
 * that type are filled in by LangChain4j and are <em>not</em> shown to the model.
 *
 * <h2>How confinement works ({@link #resolveInside})</h2>
 * <ol>
 *   <li>Resolve the folder to its real path (symlinks followed), e.g. {@code C:\Users\schlu}.</li>
 *   <li>Resolve the requested path against it and {@code normalize()} away {@code .} and {@code ..}.
 *       An absolute path replaces the folder entirely, which is fine: it is checked like any other.</li>
 *   <li>Find the longest part of the result that exists and resolve <em>that</em> to its real path, so
 *       a symlink inside the folder that points outside is caught. The not-yet-existing rest (a file
 *       about to be created) is appended unchanged.</li>
 *   <li>The final path must start with the folder's real path; otherwise the call is refused.</li>
 * </ol>
 *
 * <h2>Not implemented yet</h2>
 * <ul>
 *   <li>Race-free checks: between the check and the actual file operation, another process could
 *       swap a directory for a symlink (a "TOCTOU" race). Opening files with {@code NOFOLLOW_LINKS} or
 *       re-checking after opening would close that gap; for a local single-user app the risk is small.</li>
 *   <li>Several folders per conversation, or read-only folders.</li>
 *   <li>Hidden-file or pattern exclusions (e.g. never touch {@code .ssh} or {@code .git} even inside the folder).</li>
 * </ul>
 */
public final class WorkFolder {

    /** Name of the {@link InvocationParameters} entry that holds the folder (a {@link Path}). */
    public static final String PARAMETER = "workFolder";

    private WorkFolder() {
    }

    public static Path defaultFolder() {
        return Settings.defaultFolder();
    }

    /** The folder for this tool call, or the default when the caller didn't set one. */
    public static Path of(InvocationParameters parameters) {
        Object folder = parameters == null ? null : parameters.get(PARAMETER);
        // Accept a Path (what ToolCallRunner passes) or a String (handy in tests); else fall back
        return folder instanceof Path p ? p : folder instanceof String s ? Path.of(s) : defaultFolder();
    }

    /**
     * Resolves {@code path} (relative to the folder, or absolute) and checks that it stays inside
     * the folder, following symlinks and {@code ..}. Throws when it would leave the folder.
     */
    public static Path resolveInside(Path folder, String path) throws IOException {
        if (!Files.isDirectory(folder)) throw new IllegalArgumentException("The working folder does not exist: " + folder);
        Path root = folder.toRealPath(); // step 1: the folder itself, symlinks resolved
        // step 2: an empty path means the folder itself; normalize() removes "." and "a/.." segments
        Path target = (path == null || path.isBlank() ? root : root.resolve(path.strip())).normalize();

        // step 3: follow symlinks on the part that exists; the rest (a file about to be created) is appended as is
        Path real = realPath(target);

        // step 4: Path.startsWith compares whole name segments, so C:\work2 does NOT start with C:\work
        if (!real.startsWith(root)) {
            throw new IllegalArgumentException("'" + path + "' is outside the working folder " + root
                    + ". Only paths inside it are allowed.");
        }
        return real;
    }

    /** The path with symlinks resolved on the part that exists; the part that doesn't exist yet is appended as is. */
    private static Path realPath(Path target) throws IOException {
        Path existing = target;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) existing = existing.getParent();
        return existing == null ? target : existing.toRealPath().resolve(existing.relativize(target)).normalize();
    }

    // --- Folders file tools refuse, even inside the working folder ---

    /**
     * Name of the {@link InvocationParameters} entry with more folders to refuse (a {@code List<Path>}),
     * on top of {@link Settings#skillsFolder()}, which is always refused. {@code Agent} passes the
     * skill folder its loader reads, in case it is another one.
     */
    public static final String DENIED_PARAMETER = "deniedFolders";

    /**
     * Like {@link #resolveInside(Path, String)} for the call's folder ({@link #of}), and also refuses a
     * path at or inside a denied folder: the user's skill folder ({@link Settings#skillsFolder()}, plus
     * {@link #DENIED_PARAMETER}). Skill files change only through the skill builder, after the user's
     * review, never through a file tool, even when the trusted folder contains the skill folder.
     */
    public static Path resolveInside(InvocationParameters parameters, String path) throws IOException {
        Path real = resolveInside(of(parameters), path);
        for (Path denied : deniedFolders(parameters)) {
            if (real.startsWith(denied)) throw deniedError(path, denied);
        }
        return real;
    }

    /**
     * Refuses a target that has a denied folder inside it, for tools that remove or move a whole tree
     * ({@code deletePath}, {@code movePath}): deleting the skill folder's parent would delete the skills.
     *
     * @param target a path {@link #resolveInside(InvocationParameters, String)} accepted
     */
    public static Path requireNoDeniedInside(InvocationParameters parameters, Path target, String path) throws IOException {
        for (Path denied : deniedFolders(parameters)) {
            if (denied.startsWith(target)) throw deniedError(path, denied);
        }
        return target;
    }

    /** The denied folders as real paths (the parts that exist resolved like {@link #resolveInside}). */
    private static List<Path> deniedFolders(InvocationParameters parameters) throws IOException {
        List<Path> folders = new ArrayList<>();
        folders.add(Settings.skillsFolder());
        if (parameters != null && parameters.get(DENIED_PARAMETER) instanceof List<?> extra) {
            for (Object folder : extra) if (folder instanceof Path p) folders.add(p);
        }
        List<Path> real = new ArrayList<>();
        for (Path folder : folders) real.add(realPath(folder.toAbsolutePath().normalize()));
        return real;
    }

    private static IllegalArgumentException deniedError(String path, Path denied) {
        return new IllegalArgumentException("'" + path + "' is in or contains the skills folder " + denied
                + ": skill files change only through the skill builder (buildSkill), not through file tools.");
    }
}
