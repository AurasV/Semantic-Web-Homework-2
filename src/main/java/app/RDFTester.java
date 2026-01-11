package app;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.RDFDataMgr; // Modern way to read data

public class RDFTester {
    public static void main(String[] args) {
        Model model = ModelFactory.createDefaultModel();

        RDFDataMgr.read(model, "src/main/resources/data.rdf");

        System.out.println("--- Successfully loaded the graph! ---");
        model.write(System.out, "TURTLE");
    }
}