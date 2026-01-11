package app;

import org.apache.jena.rdf.model.*;
import org.apache.jena.riot.RDFDataMgr;
import org.springframework.stereotype.Service;

@Service
public class JenaService {
    // path to our RDF file with book data
    private final String rdfPath = "src/main/resources/data.rdf";
    private Model model;

    public JenaService() {
        // load the RDF file on startup
        this.model = ModelFactory.createDefaultModel();
        RDFDataMgr.read(this.model, rdfPath);
    }

    public Model getModel() {
        return model;
    }

    // saves changes to the RDF file (called when adding/editing books)
    public void save() {
        try {
            java.io.File file = new java.io.File(rdfPath);
            if (file.getParentFile() != null) file.getParentFile().mkdirs();

            try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
                // write in RDF/XML format
                org.apache.jena.riot.RDFDataMgr.write(out, model, org.apache.jena.riot.RDFFormat.RDFXML_ABBREV);
            }
        } catch (Exception e) {
            System.err.println("FAILED TO SAVE RDF: " + e.getMessage());
        }
    }
}