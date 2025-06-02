package dtm.discovery.finder;

import dtm.discovery.core.ClassFinderConfigurations;
import dtm.discovery.core.Processor;

import java.io.File;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class SimpleJarProcessor implements Processor {

    private final Set<Class<?>> processedClasses;
    private final ExecutorService executorService;
    private final File jarFile;
    private final ClassFinderConfigurations configurations;
    private final Set<String> jarProcessed;
    private Consumer<Throwable> errorAction = e -> {};

    public SimpleJarProcessor(Set<Class<?>> processedClasses, File jarFile) {
        this.processedClasses = processedClasses;
        this.jarFile = jarFile;
        this.executorService = Executors.newVirtualThreadPerTaskExecutor();
        this.configurations = null;
        this.jarProcessed = ConcurrentHashMap.newKeySet();
    }

    public SimpleJarProcessor(Set<Class<?>> processedClasses, File jarFile, ClassFinderConfigurations configurations) {
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
        executorService.shutdown();
    }

    @Override
    public void onError(Consumer<Throwable> action) {
        if(action != null) this.errorAction = action;
    }

    private CompletableFuture<Void> scanJar(final URL jarUrl){
        try(
                JarFile jarFile = new JarFile(jarUrl.getFile());
                URLClassLoader classLoader = URLClassLoader.newInstance(new URL[] { jarUrl });
        ){
            Enumeration<JarEntry> entries = jarFile.entries();
            List<CompletableFuture<?>> localTasks = new ArrayList<>();
            List<CompletableFuture<?>> subJarFutures = new ArrayList<>();

            while (entries.hasMoreElements()){
                final JarEntry entry = entries.nextElement();
                final String entryName = entry.getName();

                CompletableFuture<Void> task = CompletableFuture.runAsync(() -> {
                    try {
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
        return configurations.getIgnorePackges()
                .stream()
                .anyMatch(className::startsWith);
    }

    private boolean ignoreJar(String jarPath){
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
                    processedClasses.add(clazz);
                }
            } else {
                processedClasses.add(clazz);
            }
        }else{
            processedClasses.add(clazz);
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

}
