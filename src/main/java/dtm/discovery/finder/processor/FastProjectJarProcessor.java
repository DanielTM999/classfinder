package dtm.discovery.finder.processor;

import dtm.discovery.core.ClassFinderConfigurations;
import dtm.discovery.core.Processor;
import dtm.discovery.stereotips.ClassFinderStereotips;

import java.net.URL;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class FastProjectJarProcessor implements Processor {

    private final ExecutorService executorService;
    private final URL jarUrl;
    private final Set<Class<?>> processedClasses;
    private final ClassFinderConfigurations configurations;
    private final String packageName;
    private final String packagePath;
    private Predicate<ClassFinderStereotips> acept;
    private Consumer<Throwable> errorAction = e -> {};

    public FastProjectJarProcessor(
            URL jarUrl,
            Set<Class<?>> processedClasses,
            String packageName,
            ClassFinderConfigurations configurations
    ) {
        this.jarUrl = jarUrl;
        this.processedClasses = processedClasses;
        this.configurations = configurations;
        this.packageName = packageName;
        this.executorService = Executors.newVirtualThreadPerTaskExecutor();
        String path = packageName.replace('.', '/');
        if (!path.endsWith("/")) {
            path += "/";
        }
        this.packagePath = path;
    }


    @Override
    public void execute() throws Exception {
        if (acept != null && !acept.test(new ClassFinderStereotips() {
            @Override
            public URL getArchiverUrl() { return jarUrl; }
            @Override
            public StereotipsProtocols getArchiverProtocol() { return StereotipsProtocols.JAR; }
        })) return;

        try (JarFile jarFile = new JarFile(Paths.get(jarUrl.toURI()).toFile())) {
            Enumeration<JarEntry> entries = jarFile.entries();
            List<CompletableFuture<Void>> loadTasks = new ArrayList<>();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String entryName = entry.getName();

                if (entry.isDirectory() || !entryName.endsWith(".class")) {
                    continue;
                }

                if (!packagePath.isEmpty() && !entryName.startsWith(packagePath)) {
                    continue;
                }

                loadTasks.add(CompletableFuture.runAsync(() -> processEntry(entryName), executorService));
            }

            CompletableFuture.allOf(loadTasks.toArray(new CompletableFuture[0])).join();

        } catch (Exception e) {
            errorAction.accept(e);
        } finally {
            closeExecutor();
        }
    }

    @Override
    public void onError(Consumer<Throwable> action) {
        if (action != null) this.errorAction = action;
    }

    @Override
    public void acept(Predicate<ClassFinderStereotips> acept) {
        this.acept = acept;
    }

    private void processEntry(String entryName) {
        try {
            String className = entryName.substring(0, entryName.length() - 6).replace('/', '.');

            if (ignore(className)) return;

            if (!configurations.getAnonimousClass() && className.contains("$")) {
                return;
            }

            Class<?> clazz = tryLoad(className);
            if (clazz != null) {
                injectToClassList(clazz);
            }
        } catch (Exception e) {
            errorAction.accept(e);
        }
    }

    private Class<?> tryLoad(String className) {
        try {
            return Class.forName(className, false, Thread.currentThread().getContextClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        } catch (Exception e) {
            errorAction.accept(e);
            return null;
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

    private boolean ignore(String className) {
        for (String ignoredPackage : configurations.getIgnorePackges()) {
            if (className.startsWith(ignoredPackage)) return true;
        }
        return false;
    }

    private void closeExecutor() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
