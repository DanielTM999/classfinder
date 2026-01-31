package dtm.discovery.finder.processor;

import dtm.discovery.core.ClassFinderConfigurations;
import dtm.discovery.core.Processor;
import dtm.discovery.stereotips.ClassFinderStereotips;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class JarProcessor implements Processor {

    private final ExecutorService executorService;
    private final URL jarUrl;
    private final Set<Class<?>> processedClasses;
    private final Set<String> jarProcessed;
    private final ClassFinderConfigurations configurations;
    private Predicate<ClassFinderStereotips> acept;
    private final String packageName;
    private Consumer<Throwable> errorAction = e -> {};

    public JarProcessor(
            URL jarUrl,
            Set<Class<?>> processedClasses,
            Set<String> jarProcessed,
            String packageName,
            ClassFinderConfigurations configurations
    ) {
        this.jarUrl = jarUrl;
        this.processedClasses = processedClasses;
        this.jarProcessed = jarProcessed;
        this.configurations = configurations;
        this.packageName = packageName;
        this.executorService = Executors.newVirtualThreadPerTaskExecutor();
    }


    @Override
    public void execute() throws Exception{
        try {
            CompletableFuture<Void> future = encontrarClassesNoPacoteDentroDoJar(jarUrl, packageName, true);
            future.join();
        } finally {
            executorService.shutdown();
            if (!executorService.awaitTermination(1, TimeUnit.MINUTES)) {
                executorService.shutdownNow();
            }
        }
    }

    @Override
    public void onError(Consumer<Throwable> action) {
        if(action != null) this.errorAction = action;
    }

    @Override
    public void acept(Predicate<ClassFinderStereotips> acept) {
        this.acept = (acept != null) ? acept : (e) -> true;
    }

    private CompletableFuture<Void> encontrarClassesNoPacoteDentroDoJar(URL jarUrl, String pacote, boolean ismainJar) {
        if(!acept.test(new ClassFinderStereotips() {
            @Override
            public URL getArchiverUrl() {
                return jarUrl;
            }

            @Override
            public StereotipsProtocols getArchiverProtocol() {
                return StereotipsProtocols.JAR;
            }
        }))return CompletableFuture.completedFuture(null);

        try(JarFile jarFile = new JarFile(Paths.get(jarUrl.toURI()).toFile())) {
            Enumeration<JarEntry> entries = jarFile.entries();
            List<CompletableFuture<?>> localTasks = new ArrayList<>();
            List<CompletableFuture<?>> subJarFutures = new ArrayList<>();

            while (entries.hasMoreElements()) {
                final JarEntry entry = entries.nextElement();
                final String entryName = entry.getName();

                CompletableFuture<Void> task = CompletableFuture.runAsync(() -> {
                    try {
                        if (entryName.regionMatches(true, 0, "META-INF/versions/", 0, "META-INF/versions/".length()) || entryName.endsWith("module-info.class")) {
                            return;
                        }

                        if((entryName.startsWith(pacote.replace('.', '/')) || configurations.getAllElements()) && entryName.endsWith(".class")) {
                            String className = entryName.replace('/', '.').replace(".class", "");
                            if (!ignore(className)) {
                                if (configurations.getAnonimousClass() || !className.contains("$")) {
                                    Class<?> clazz = tryLoad(className);
                                    if(clazz != null) {
                                        injectToClassList(clazz);
                                    }
                                }
                            }
                        } else if(entryName.endsWith(".jar")) {
                            if (!configurations.ignoreSubJars() || (ismainJar && !configurations.ignoreMainJar())) {
                                String jarInternalPath = "jar:file:" + jarUrl.getFile().replace("\\", "/") + "!/" + entryName;
                                String decodedPath = URLDecoder.decode(jarInternalPath, StandardCharsets.UTF_8);

                                if (ignoreJar(decodedPath, ismainJar)) return;
                                URL jarUrlInternal = URI.create(decodedPath).toURL();
                                String jarKey = jarUrlInternal.toExternalForm();
                                if (jarProcessed.add(jarKey)) {
                                    CompletableFuture<?> subJarFuture = encontrarClassesNoPacoteDentroDoJar(jarUrlInternal, pacote, false);
                                    subJarFutures.add(subJarFuture);
                                }
                            }
                        }
                    } catch (Exception e) {
                        errorAction.accept(e);
                    }
                }, executorService);
                localTasks.add(task);
            }


            CompletableFuture.allOf(localTasks.toArray(new CompletableFuture[0])).join();
        } catch (Exception e) {
            errorAction.accept(e);
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.completedFuture(null);
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

    private Class<?> tryLoad(String className){
        try {
            Class<?> clazz = null;
            try {
                return Class.forName(className, false, Thread.currentThread().getContextClassLoader());
            } catch (ClassNotFoundException | LinkageError ignored){

            } catch (Exception e) {
                errorAction.accept(e);
            }
        } catch (Exception e) {
            errorAction.accept(e);
        }
        return null;
    }

    private boolean ignore(String className){
        return configurations.getIgnorePackges()
                .stream()
                .anyMatch(className::startsWith);
    }

    private boolean ignoreJar(String jarPath, boolean isMainJar){
        String lowerJarPath = jarPath.toLowerCase();
        
        if(isMainJar && !configurations.ignoreMainJar()) return false;

        return configurations.getIgnoreJarsTerms()
                .stream()
                .map(String::toLowerCase)
                .anyMatch(lowerJarPath::contains);
    }

}
