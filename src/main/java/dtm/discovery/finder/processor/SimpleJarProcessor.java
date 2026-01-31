package dtm.discovery.finder.processor;

import dtm.discovery.core.ClassFinderConfigurations;
import dtm.discovery.core.Processor;
import dtm.discovery.stereotips.ClassFinderStereotips;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class SimpleJarProcessor implements Processor {

    private final Map<File, Set<Class<?>>> processedClasses;
    private final ExecutorService executorService;
    private final File jarFile;
    private final ClassFinderConfigurations configurations;
    private final Set<String> jarProcessed;
    private Consumer<Throwable> errorAction = e -> {};
    private Predicate<ClassFinderStereotips> acept;
    private final List<URLClassLoader> classLoadersToClose = Collections.synchronizedList(new ArrayList<>());


    public SimpleJarProcessor(Map<File, Set<Class<?>>> processedClasses, File jarFile) {
        this.processedClasses = processedClasses;
        this.jarFile = jarFile;
        this.executorService = Executors.newVirtualThreadPerTaskExecutor();
        this.configurations = null;
        this.jarProcessed = ConcurrentHashMap.newKeySet();
    }

    public SimpleJarProcessor(Map<File, Set<Class<?>>> processedClasses, File jarFile, ClassFinderConfigurations configurations) {
        this.processedClasses = processedClasses;
        this.jarFile = jarFile;
        this.executorService = Executors.newVirtualThreadPerTaskExecutor();
        this.configurations = configurations;
        this.jarProcessed = ConcurrentHashMap.newKeySet();
    }

    @Override
    public void execute() throws Exception {
        URL jarUrl = this.jarFile.toURI().toURL();
        CompletableFuture<Void> future = scanJar(jarUrl);
        future.join();
        for (URLClassLoader loader : classLoadersToClose) {
            try {
                loader.close();
            } catch (IOException e) {
                errorAction.accept(e);
            }
        }
        classLoadersToClose.clear();
        executorService.shutdown();
    }

    @Override
    public void onError(Consumer<Throwable> action) {
        if(action != null) this.errorAction = action;
    }

    @Override
    public void acept(Predicate<ClassFinderStereotips> acept) {
        this.acept = (acept != null) ? acept : (e) -> true;
    }

    private CompletableFuture<Void> scanJar(final URL jarUrl){
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
        try(
                JarFile jarFile = new JarFile(Paths.get(jarUrl.toURI()).toFile())
        ){
            URLClassLoader classLoader = URLClassLoader.newInstance(new URL[] { jarUrl });
            classLoadersToClose.add(classLoader);
            Enumeration<JarEntry> entries = jarFile.entries();
            List<CompletableFuture<?>> localTasks = new ArrayList<>();
            List<CompletableFuture<?>> subJarFutures = new ArrayList<>();

            while (entries.hasMoreElements()){
                final JarEntry entry = entries.nextElement();
                final String entryName = entry.getName();

                CompletableFuture<Void> task = CompletableFuture.runAsync(() -> {
                    try {
                        if (entryName.regionMatches(true, 0, "META-INF/versions/", 0, "META-INF/versions/".length()) || entryName.endsWith("module-info.class")) {
                            return;
                        }

                        if(entryName.endsWith(".class")) {
                            String className = entryName.replace('/', '.').replace(".class", "");
                            processClass(className, classLoader);
                        } else if (entryName.endsWith(".jar")) {
                            if (!getIgnoreSubJars()) {
                                String jarInternalPath = "jar:file:" + jarUrl.getFile().replace("\\", "/") + "!/" + entryName;
                                String decodedPath = URLDecoder.decode(jarInternalPath, StandardCharsets.UTF_8);
                                if (ignoreJar(decodedPath)) return;
                                URL jarUrlInternal = URI.create(decodedPath).toURL();
                                String jarKey = jarUrlInternal.toExternalForm();
                                if (jarProcessed.add(jarKey)) {
                                    CompletableFuture<?> subJarFuture = scanJar(jarUrlInternal);
                                    subJarFutures.add(subJarFuture);
                                }
                            }
                        }
                    }catch (Exception e) {
                        errorAction.accept(e);
                    }
                }, executorService);
                localTasks.add(task);
            }
            
            return CompletableFuture.allOf(
                    CompletableFuture.allOf(localTasks.toArray(new CompletableFuture[0])),
                    CompletableFuture.allOf(subJarFutures.toArray(new CompletableFuture[0]))
            );
        } catch (Exception e) {
            errorAction.accept(e);
            return CompletableFuture.completedFuture(null);
        }
    }

    private void processClass(String className, URLClassLoader classLoader){
        if (!ignore(className)) {
            if (getAnonimousClass() || !className.contains("$")) {
                Class<?> clazz = tryLoad(className, classLoader);
                if(clazz != null) {
                    injectToClassList(clazz);
                }
            }
        }
    }

    private Class<?> tryLoad(String className, URLClassLoader classLoader){
       try{
           return classLoader.loadClass(className);
       }catch (Exception e){
           return null;
       }
    }

    private boolean ignore(String className){
        if(configurations == null) return false;
        return configurations.getIgnorePackges()
                .stream()
                .anyMatch(className::startsWith);
    }

    private boolean ignoreJar(String jarPath){
        if(configurations == null) return false;
        String lowerJarPath = jarPath.toLowerCase();
        return configurations.getIgnoreJarsTerms()
                .stream()
                .map(String::toLowerCase)
                .anyMatch(lowerJarPath::contains);
    }

    private void injectToClassList(Class<?> clazz) {

        if(configurations != null){
            if (this.configurations.getFilterByAnnotation() != null) {
                if (clazz.isAnnotationPresent(this.configurations.getFilterByAnnotation())) {
                    addToProcessedClasses(jarFile, clazz);
                }
            } else {
                addToProcessedClasses(jarFile, clazz);
            }
        }else{
            addToProcessedClasses(jarFile, clazz);
        }

    }

    private boolean getAnonimousClass(){
        if(configurations == null) return true;
        return configurations.getAnonimousClass();
    }

    private boolean getIgnoreSubJars(){
        if(configurations == null) return true;
        return configurations.ignoreSubJars();
    }

    private void addToProcessedClasses(File rootDir, Class<?> clazz) {
        this.processedClasses.computeIfAbsent(rootDir, k -> ConcurrentHashMap.newKeySet()).add(clazz);
    }

}
