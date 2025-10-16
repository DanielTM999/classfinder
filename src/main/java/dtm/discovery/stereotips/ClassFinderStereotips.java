package dtm.discovery.stereotips;

import java.net.URL;

public interface ClassFinderStereotips {
    URL getArchiverUrl();
    StereotipsProtocols getArchiverProtocol();

    enum StereotipsProtocols {
        FILE,
        JAR,
        DIR
    }
}
