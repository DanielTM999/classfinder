package dtm.discovery.finder;

import dtm.discovery.core.Processor;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class SimpleJarProcessor implements Processor {

    private final Set<Class<?>> processedClasses;
    private final File jarFile;
    private Consumer<Throwable> errorAction = e -> {};

    public SimpleJarProcessor(Set<Class<?>> processedClasses, File jarFile) {
        this.processedClasses = processedClasses;
        this.jarFile = jarFile;
    }

    @Override
    public void execute() throws Exception {
        try(
            JarFile jarFile = new JarFile(this.jarFile);
            URLClassLoader classLoader = URLClassLoader.newInstance(new URL[] { this.jarFile.toURI().toURL() })
        ){
            List<JarEntry> entries = Collections.list(jarFile.entries());

            entries.parallelStream()
                    .filter(entry -> entry.getName().endsWith(".class"))
                    .forEach(entry -> {
                        String className = entry.getName().replace('/', '.').replace(".class", "");
                        try {
                            Class<?> clazz = classLoader.loadClass(className);
                            this.processedClasses.add(clazz);
                        } catch (ClassNotFoundException | NoClassDefFoundError e) {
                            errorAction.accept(e);
                        }
                    });
        }
    }

    @Override
    public void onError(Consumer<Throwable> action) {
        if(action != null) this.errorAction = action;
    }
}
