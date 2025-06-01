package dtm.discovery.finder;

import dtm.discovery.core.ClassFinderConfigurations;
import dtm.discovery.core.Processor;
import java.io.File;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class DirectoryProcessor implements Processor {

    private final File root;
    private final String packageName;
    private final Set<Class<?>> processedClasses;
    private final ClassFinderConfigurations configurations;
    private final ExecutorService executorService;
    private Consumer<Throwable> errorAction = e -> {};

    public DirectoryProcessor(
            File root,
            String packageName,
            Set<Class<?>> processedClasses,
            ClassFinderConfigurations configurations
    ) {
        this.root = root;
        this.packageName = packageName;
        this.processedClasses = processedClasses;
        this.configurations = configurations;
        this.executorService = Executors.newVirtualThreadPerTaskExecutor();
    }

    @Override
    public void execute() throws Exception{
        if (root == null || !root.isDirectory()) return;
        recusiveSearch(root, packageName);
        executorService.shutdown();
    }

    @Override
    public void onError(Consumer<Throwable> action) {
        if(action != null) this.errorAction = action;
    }

    private void recusiveSearch(File directory, String pacote){
        if (directory == null) return;

        File[] files = directory.listFiles();
        if (files == null) files = new File[0];
        try  {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (File file : files) {
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        if (file.isFile() && file.getName().endsWith(".class")) {
                            String className = pacote + "." + file.getName().replace(".class", "");
                            if (configurations.getAnonimousClass() || !className.contains("$")) {
                                injectToClassList(Class.forName(className));
                            }
                        } else if (file.isDirectory()) {
                            final String newPackage = pacote + "." + file.getName();
                            recusiveSearch(file, newPackage);
                        }
                    } catch (Exception e) {
                        errorAction.accept(e);
                    }
                }, executorService));
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }catch (Exception e){
            errorAction.accept(e);
        }

    }

    private void injectToClassList(Class<?> clazz) {
        if (this.configurations.getFilterByAnnotation() != null) {
            if (clazz.isAnnotationPresent(this.configurations.getFilterByAnnotation())) {
                processedClasses.add(clazz);
            }
        } else {
            processedClasses.add(clazz);
        }
    }

}
