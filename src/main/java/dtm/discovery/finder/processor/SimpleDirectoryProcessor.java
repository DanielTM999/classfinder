package dtm.discovery.finder.processor;

import dtm.discovery.core.Processor;
import dtm.discovery.stereotips.ClassFinderStereotips;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class SimpleDirectoryProcessor implements Processor {

    private final File root;
    private final ExecutorService executor;
    private final Map<File, Set<Class<?>>> processedClasses;
    private final Map<File, URLClassLoader> fallbackClassLoaders = new ConcurrentHashMap<>();
    private Consumer<Throwable> errorAction = e -> {};
    private Predicate<ClassFinderStereotips> acept;

    public SimpleDirectoryProcessor(File root,  Map<File, Set<Class<?>>> processedClasses) {
        this.root = root;
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.processedClasses = processedClasses;
    }

    @Override
    public void execute() throws Exception {
        List<CompletableFuture<Void>> allTasks = new ArrayList<>();

        try {
            if (root.exists() && root.isDirectory()) {
                search(root.listFiles(), allTasks);
            }

            CompletableFuture.allOf(allTasks.toArray(new CompletableFuture[0])).join();
        } finally {
            executor.shutdown();
            closeFallbackClassLoaders();
        }
    }

    private void closeFallbackClassLoaders() {
        for (URLClassLoader classLoader : fallbackClassLoaders.values()) {
            try {
                classLoader.close();
            } catch (IOException e) {
                errorAction.accept(e);
            }
        }
        fallbackClassLoaders.clear();
    }

    @Override
    public void onError(Consumer<Throwable> action) {
        if(action != null) this.errorAction = action;
    }

    @Override
    public void acept(Predicate<ClassFinderStereotips> acept) {
        this.acept = (acept != null) ? acept : (e) -> true;
    }

    private void search(File[] files, List<CompletableFuture<Void>> tasks){
        for (File file : files){
            if(!acept.test(new ClassFinderStereotips() {
                @Override
                public URL getArchiverUrl() {
                    try {
                        return file.toURI().toURL();
                    } catch (MalformedURLException e) {
                       return null;
                    }
                }

                @Override
                public StereotipsProtocols getArchiverProtocol() {
                    if(file.isDirectory()) return StereotipsProtocols.DIR;
                    String path = file.getName();
                    return (file.isFile() && path.endsWith(".jar")) ? StereotipsProtocols.JAR : StereotipsProtocols.FILE;
                }
            })) continue;
            if (file.isDirectory()) {
                search(file.listFiles(), tasks);
            } else {
                tasks.add(CompletableFuture.runAsync(() -> loadFile(file), executor));
            }
        }
    }

    private void loadFile(File file){
        String path = file.getName();
        if (path.endsWith(".class")) {
            loadClassFromClassFile(file, root);
        } else if (path.endsWith(".jar")) {
           try{
               Processor processor = new SimpleJarProcessor(processedClasses, file);
               processor.onError(errorAction);
               processor.acept(acept);
               processor.execute();
           }catch (Exception e){
               errorAction.accept(e);
           }
        }
    }

    public void loadClassFromClassFile(File file, File rootDir) {
        try {
            File parentDir = file.getParentFile();
            if (parentDir == null) {
                throw new IOException("Erro: Caminho inválido.");
            }
            List<String> classNames = getPossibleClassNamesFromFile(file, rootDir);
            for(String className : classNames){
                try {
                    Class<?> clazz = Class.forName(className, false, getClass().getClassLoader());
                    addToProcessedClasses(rootDir, clazz);
                } catch (ClassNotFoundException e) {
                    try {
                        Class<?> clazz = getClassLoaderForFile(file).loadClass(className);
                        addToProcessedClasses(rootDir, clazz);
                    }catch(ClassNotFoundException | NoClassDefFoundError ignored){}
                }
            }
        } catch (Exception e) {
            errorAction.accept(e);
        }
    }

    private List<String> getPossibleClassNamesFromFile(File file, File rootDir) {
        List<String> names = new ArrayList<>();
        File currentDir = file.getParentFile();
        String filePath = file.getAbsolutePath();

        while (currentDir != null && !currentDir.equals(rootDir)) {
            String basePath = currentDir.getAbsolutePath();

            if (filePath.startsWith(basePath)) {
                String relativePath = filePath.substring(basePath.length() + 1, filePath.length() - 6);

                String className = relativePath.replace(File.separatorChar, '.');

                names.add(className);
            }

            currentDir = currentDir.getParentFile();
        }

        return names;
    }

    /**
     * Um unico URLClassLoader por diretorio, reaproveitado entre as tentativas e entre os
     * arquivos daquele diretorio. Antes era criado (e fechado) um classloader por tentativa
     * de nome, o que dava O(profundidade) classloaders por classe.
     */
    private URLClassLoader getClassLoaderForFile(File file) throws IOException {
        File parentDir = file.getParentFile();
        if (parentDir == null) throw new IOException("Diretorio pai invalido.");

        URLClassLoader cached = fallbackClassLoaders.get(parentDir);
        if (cached != null) return cached;

        URL url = parentDir.toURI().toURL();
        URLClassLoader created = new URLClassLoader(new URL[]{url}, getClass().getClassLoader());

        URLClassLoader previous = fallbackClassLoaders.putIfAbsent(parentDir, created);
        if (previous != null) {
            created.close();
            return previous;
        }

        return created;
    }

    private void addToProcessedClasses(File rootDir, Class<?> clazz) {
        this.processedClasses.computeIfAbsent(rootDir, k -> ConcurrentHashMap.newKeySet()).add(clazz);
    }

}
