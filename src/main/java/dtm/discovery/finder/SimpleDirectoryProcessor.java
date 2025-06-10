package dtm.discovery.finder;

import dtm.discovery.core.Processor;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class SimpleDirectoryProcessor implements Processor {

    private final File root;
    private final ExecutorService executor;
    private final Set<Class<?>> processedClasses;
    private Consumer<Throwable> errorAction = e -> {};

    public SimpleDirectoryProcessor(File root, Set<Class<?>> processedClasses) {
        this.root = root;
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.processedClasses = processedClasses;
    }

    @Override
    public void execute() throws Exception {
        List<CompletableFuture<Void>> allTasks = new ArrayList<>();

        if (root.exists() && root.isDirectory()) {
            search(root.listFiles(), allTasks);
        }

        CompletableFuture.allOf(allTasks.toArray(new CompletableFuture[0])).join();
        executor.shutdown();
    }

    @Override
    public void onError(Consumer<Throwable> action) {
        if(action != null) this.errorAction = action;
    }

    private void search(File[] files, List<CompletableFuture<Void>> tasks){
        for (File file : files){
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
                    this.processedClasses.add(clazz);
                } catch (ClassNotFoundException e) {
                    try(URLClassLoader classLoader = getClassLoaderForFile(file)) {
                        Class<?> clazz = classLoader.loadClass(className);
                        this.processedClasses.add(clazz);
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

    private URLClassLoader getClassLoaderForFile(File file) throws IOException {
        File parentDir = file.getParentFile();
        if (parentDir == null) throw new IOException("Diretório pai inválido.");
        URL url = parentDir.toURI().toURL();
        return new URLClassLoader(new URL[]{url}, getClass().getClassLoader());
    }
}
