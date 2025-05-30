package dtm.discovery.finder;

import dtm.discovery.core.ClassFinderConfigurations;
import dtm.discovery.core.Processor;

import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Set;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class JarProcessor implements Processor {

    private final URL jarUrl;
    private final Set<Class<?>> processedClasses;
    private final Set<String> jarProcessed;
    private final ClassFinderConfigurations configurations;
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
    }


    @Override
    public void execute() throws Exception{
        encontrarClassesNoPacoteDentroDoJar(jarUrl, packageName);
    }

    @Override
    public void onError(Consumer<Throwable> action) {
        if(action != null) this.errorAction = action;
    }

    private void encontrarClassesNoPacoteDentroDoJar(URL jarUrl, String pacote) {
        try(JarFile jarFile = new JarFile(jarUrl.getFile())){
            Enumeration<JarEntry> entries = jarFile.entries();

            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String entryName = entry.getName();

                if((entryName.startsWith(pacote.replace('.', '/')) || configurations.getAllElements()) && entryName.endsWith(".class")) {

                    try {
                        String className = entryName.replace('/', '.').replace(".class", "");
                        if (!ignore(className)){
                            if (configurations.getAnonimousClass() || !className.contains("$")) {
                                Class<?> clazz = tryLoad(className);

                                if(clazz != null){
                                    injectToClassList(clazz);
                                }
                            }
                        }
                    } catch (Exception e) {
                        errorAction.accept(e);
                    }
                }else if(entryName.endsWith(".jar")){
                    if (!configurations.ignoreSubJars()) {
                        String jarInternalPath = "jar:file:" + jarUrl.getFile().replace("\\", "/") + "!/" + entryName;
                        String decodedPath = URLDecoder.decode(jarInternalPath, StandardCharsets.UTF_8);
                        if (ignoreJar(decodedPath)) return;
                        URL jarUrlInternal = URI.create(decodedPath).toURL();
                        String jarKey = jarUrlInternal.toExternalForm();
                        if (jarProcessed.add(jarKey)) {
                            encontrarClassesNoPacoteDentroDoJar(jarUrlInternal, pacote);
                        }
                    }
                }

            }
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

    private Class<?> tryLoad(String className){
        try {
            Class<?> clazz = null;
            try {
                return Class.forName(className, false, Thread.currentThread().getContextClassLoader());
            } catch (Throwable e) {
                errorAction.accept(e);
            }
        } catch (Throwable e) {
            errorAction.accept(e);
        }
        return null;
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

}
