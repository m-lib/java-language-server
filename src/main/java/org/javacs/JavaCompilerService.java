package org.javacs;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import javax.tools.*;
import org.javacs.lsp.SymbolInformation;

class JavaCompilerService implements CompilerProvider {
    // Not modifiable! If you want to edit these, you need to create a new instance
    final Set<Path> classPath, docPath;
    final Set<String> addExports;
    final ReusableCompiler compiler = new ReusableCompiler();
    final Docs docs;
    final Set<String> jdkClasses = ScanClassPath.jdkTopLevelClasses(), classPathClasses;
    // Diagnostics from the last compilation task
    final List<Diagnostic<? extends JavaFileObject>> diags = new ArrayList<>();
    // Use the same file manager for multiple tasks, so we don't repeatedly re-compile the same files
    // TODO intercept files that aren't in the batch and erase method bodies so compilation is faster
    final SourceFileManager fileManager;

    JavaCompilerService(Set<Path> classPath, Set<Path> docPath, Set<String> addExports) {
        System.err.println("Class path:");
        for (var p : classPath) {
            System.err.println("  " + p);
        }
        System.err.println("Doc path:");
        for (var p : docPath) {
            System.err.println("  " + p);
        }
        // classPath can't actually be modified, because JavaCompiler remembers it from task to task
        this.classPath = Collections.unmodifiableSet(classPath);
        this.docPath = Collections.unmodifiableSet(docPath);
        this.addExports = Collections.unmodifiableSet(addExports);
        this.docs = new Docs(docPath);
        this.classPathClasses = ScanClassPath.classPathTopLevelClasses(classPath);
        this.fileManager = new SourceFileManager();
    }

    private CompileBatch cachedCompile;
    private Map<JavaFileObject, Long> cachedModified = new HashMap<>();

    private boolean needsCompile(Collection<? extends JavaFileObject> sources) {
        if (cachedModified.size() != sources.size()) {
            return true;
        }
        for (var f : sources) {
            if (!cachedModified.containsKey(f)) {
                return true;
            }
            if (f.getLastModified() != cachedModified.get(f)) {
                return true;
            }
        }
        return false;
    }

    private void loadCompile(Collection<? extends JavaFileObject> sources) {
        if (cachedCompile != null) {
            if (!cachedCompile.closed) {
                throw new RuntimeException("Compiler is still in-use!");
            }
            cachedCompile.borrow.close();
        }
        cachedCompile = doCompile(sources);
        cachedModified.clear();
        for (var f : sources) {
            cachedModified.put(f, f.getLastModified());
        }
    }

    CompileBatch doCompile(Collection<? extends JavaFileObject> sources) {
        if (sources.isEmpty()) throw new RuntimeException("empty sources");
        var firstAttempt = new CompileBatch(this, sources);
        var addFiles = firstAttempt.needsAdditionalSources();
        if (addFiles.isEmpty()) return firstAttempt;
        // If the compiler needs additional source files that contain package-private files
        LOG.info("...need to recompile with " + addFiles);
        firstAttempt.close();
        firstAttempt.borrow.close();
        var moreSources = new ArrayList<JavaFileObject>();
        moreSources.addAll(sources);
        for (var add : addFiles) {
            moreSources.add(new SourceFileObject(add));
        }
        return new CompileBatch(this, moreSources);
    }

    CompileBatch compileBatch(Collection<? extends JavaFileObject> sources) {
        if (needsCompile(sources)) {
            loadCompile(sources);
        } else {
            LOG.info("...using cached compile");
        }
        return cachedCompile;
    }

    List<SymbolInformation> findSymbols(String query, int limit) {
        LOG.info(String.format("Searching for `%s`...", query));
        var result = new ArrayList<SymbolInformation>();
        var files = FileStore.all();
        var checked = 0;
        var parsed = 0;
        for (var file : files) {
            checked++;
            // First do a fast check if the query matches anything in a file
            if (!StringSearch.containsWordMatching(file, query)) continue;
            // Parse the file and check class members for matches
            LOG.info(String.format("...%s contains text matches", file.getFileName()));
            var parse = Parser.parseFile(file);
            var symbols = parse.findSymbolsMatching(query);
            parsed++;
            // If we confirm matches, add them to the results
            if (symbols.size() > 0) {
                LOG.info(String.format("...found %d occurrences", symbols.size()));
            }
            result.addAll(symbols);
            // If results are full, stop
            if (result.size() >= limit) break;
        }
        LOG.info(String.format("Found %d matches in %d/%d/%d files", result.size(), checked, parsed, files.size()));

        return result;
    }

    private static final Pattern PACKAGE_EXTRACTOR = Pattern.compile("^([a-z][_a-zA-Z0-9]*\\.)*[a-z][_a-zA-Z0-9]*");

    private String packageName(String className) {
        var m = PACKAGE_EXTRACTOR.matcher(className);
        if (m.find()) {
            return m.group();
        }
        return "";
    }

    private static final Pattern SIMPLE_EXTRACTOR = Pattern.compile("[A-Z][_a-zA-Z0-9]*$");

    private String simpleName(String className) {
        var m = SIMPLE_EXTRACTOR.matcher(className);
        if (m.find()) {
            return m.group();
        }
        return "";
    }

    private static final Cache<String, Boolean> cacheContainsWord = new Cache<>();

    private boolean containsWord(Path file, String word) {
        if (cacheContainsWord.needs(file, word)) {
            cacheContainsWord.load(file, word, StringSearch.containsWord(file, word));
        }
        return cacheContainsWord.get(file, word);
    }

    private static final Cache<Void, List<String>> cacheContainsType = new Cache<>();

    private boolean containsType(Path file, String className) {
        if (cacheContainsType.needs(file, null)) {
            var root = parse(file).root;
            var types = new ArrayList<String>();
            new FindTypeDeclarations().scan(root, types);
            cacheContainsType.load(file, null, types);
        }
        return cacheContainsType.get(file, null).contains(className);
    }

    private Cache<Void, List<String>> cacheFileImports = new Cache<>();

    private List<String> readImports(Path file) {
        if (cacheFileImports.needs(file, null)) {
            loadImports(file);
        }
        return cacheFileImports.get(file, null);
    }

    private void loadImports(Path file) {
        var list = new ArrayList<String>();
        var importClass = Pattern.compile("^import +([\\w\\.]+\\.\\w+);");
        var importStar = Pattern.compile("^import +([\\w\\.]+\\.\\*);");
        try (var lines = FileStore.lines(file)) {
            for (var line = lines.readLine(); line != null; line = lines.readLine()) {
                // If we reach a class declaration, stop looking for imports
                // TODO This could be a little more specific
                if (line.contains("class")) break;
                // import foo.bar.Doh;
                var matchesClass = importClass.matcher(line);
                if (matchesClass.matches()) {
                    list.add(matchesClass.group(1));
                }
                // import foo.bar.*
                var matchesStar = importStar.matcher(line);
                if (matchesStar.matches()) {
                    list.add(matchesStar.group(1));
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        cacheFileImports.load(file, null, list);
    }

    @Override
    public Set<String> imports() {
        var all = new HashSet<String>();
        for (var f : FileStore.all()) {
            all.addAll(readImports(f));
        }
        return all;
    }

    @Override
    public Set<String> publicTopLevelTypes() {
        var all = new HashSet<String>();
        for (var file : FileStore.all()) {
            var fileName = file.getFileName().toString();
            if (!fileName.endsWith(".java")) continue;
            var className = fileName.substring(0, fileName.length() - ".java".length());
            var packageName = FileStore.packageName(file);
            if (!packageName.isEmpty()) {
                className = packageName + "." + className;
            }
            all.add(className);
        }
        all.addAll(classPathClasses);
        all.addAll(jdkClasses);
        return all;
    }

    @Override
    public Set<String> packagePrivateTopLevelTypes(String packageName) {
        return Set.of("TODO");
    }

    private boolean containsImport(Path file, String className) {
        var packageName = packageName(className);
        if (FileStore.packageName(file).equals(packageName)) return true;
        var star = packageName + ".*";
        for (var i : readImports(file)) {
            if (i.equals(className) || i.equals(star)) return true;
        }
        return false;
    }

    @Override
    public Optional<JavaFileObject> findAnywhere(String className) {
        var file = findTypeDeclaration(className);
        if (file != NOT_FOUND) {
            return Optional.of(new SourceFileObject(file));
        }
        return docs.find(Ptr.toClass(packageName(className), simpleName(className)));
    }

    @Override
    public Path findTypeDeclaration(String className) {
        var packageName = packageName(className);
        var simpleName = simpleName(className);
        for (var f : FileStore.list(packageName)) {
            // TODO we can be more efficient when we know we're only interested in public classes, for example in
            // autocomplete
            if (containsWord(f, simpleName) && containsType(f, className)) {
                return f;
            }
        }
        return NOT_FOUND;
    }

    @Override
    public Path[] findTypeReferences(String className) {
        var packageName = packageName(className);
        var simpleName = simpleName(className);
        var candidates = new ArrayList<Path>();
        for (var f : FileStore.all()) {
            if (containsWord(f, packageName) && containsImport(f, className) && containsWord(f, simpleName)) {
                candidates.add(f);
            }
        }
        return candidates.toArray(Path[]::new);
    }

    @Override
    public Path[] findMemberReferences(String className, String memberName) {
        var candidates = new ArrayList<Path>();
        for (var f : FileStore.all()) {
            if (containsWord(f, memberName)) {
                candidates.add(f);
            }
        }
        return candidates.toArray(Path[]::new);
    }

    @Override
    public ParseTask parse(Path file) {
        var parser = Parser.parseFile(file);
        return new ParseTask(parser.task, parser.root);
    }

    @Override
    public ParseTask parse(JavaFileObject file) {
        var parser = Parser.parseJavaFileObject(file);
        return new ParseTask(parser.task, parser.root);
    }

    @Override
    public CompileTask compile(Path... files) {
        var sources = new ArrayList<JavaFileObject>();
        for (var f : files) {
            sources.add(new SourceFileObject(f));
        }
        return compile(sources);
    }

    @Override
    public CompileTask compile(Collection<? extends JavaFileObject> sources) {
        var compile = compileBatch(sources);
        return new CompileTask(compile.task, compile.roots, diags, compile::close);
    }

    private static final Logger LOG = Logger.getLogger("main");
}
