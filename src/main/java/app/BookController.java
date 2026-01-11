package app;

import org.apache.jena.query.*;
import org.apache.jena.rdf.model.*;
import org.apache.jena.riot.RDFDataMgr;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.*;

@Controller
public class BookController {
    private final JenaService jenaService;
    private final ChatService chatService;

    public BookController(JenaService jenaService, ChatService chatService) {
        this.jenaService = jenaService;
        this.chatService = chatService;
    }

    // list all books using SPARQL query
    @GetMapping("/books")
    public String listBooks(Model uiModel) {
        List<Map<String, String>> books = new ArrayList<>();
        // SPARQL query to get all books with their titles
        String queryStr = "PREFIX book: <http://example.org/book#> SELECT ?uri ?title WHERE { ?uri book:title ?title . }";
        try (QueryExecution qexec = QueryExecutionFactory.create(queryStr, jenaService.getModel())) {
            ResultSet results = qexec.execSelect();
            while (results.hasNext()) {
                QuerySolution soln = results.nextSolution();
                Map<String, String> b = new HashMap<>();
                b.put("uri", soln.getResource("uri").getURI());
                b.put("title", soln.getLiteral("title").getString());
                books.add(b);
            }
        }
        uiModel.addAttribute("books", books);
        return "book_list";
    }

    // show details for a single book + handle recommendation logic
    @GetMapping("/book-details")
    public String bookDetails(@RequestParam("uri") String uri, Model uiModel) {
        try {
            Resource res = jenaService.getModel().getResource(uri);
            // get properties safely (returns "Unknown" if not found)
            String title = getSafeProp(res, "title");
            String author = getSafeProp(res, "author");
            String genre = getSafeProp(res, "genre");
            String level = getSafeProp(res, "level");

            uiModel.addAttribute("title", title);
            uiModel.addAttribute("author", author);
            uiModel.addAttribute("genre", genre);
            uiModel.addAttribute("level", level);

            // recommendation logic based on user preferences
            // Alice: SciFi + Intermediate, Bob: Mystery + Beginner
            List<String> recs = new ArrayList<>();
            if (genre.contains("Science Fiction") && level.equalsIgnoreCase("Intermediate")) recs.add("Alice");
            if (genre.contains("Mystery") && level.equalsIgnoreCase("Beginner")) recs.add("Bob");
            uiModel.addAttribute("recs", recs);

            return "book_details";
        } catch (Exception e) {
            return "redirect:/books";
        }
    }

    private String getSafeProp(Resource res, String localName) {
        Property p = res.getModel().createProperty("http://example.org/book#" + localName);
        return res.hasProperty(p) ? res.getProperty(p).getString() : "Unknown";
    }

    // handle RDF file upload for visualization
    @PostMapping("/upload-rdf")
    public String handleFileUpload(@RequestParam("file") MultipartFile file, Model uiModel) {
        try {
            // parse uploaded file into a temporary model
            org.apache.jena.rdf.model.Model tempModel = ModelFactory.createDefaultModel();
            RDFDataMgr.read(tempModel, file.getInputStream(), org.apache.jena.riot.Lang.RDFXML);

            // replace current model with uploaded data
            jenaService.getModel().removeAll().add(tempModel);
            uiModel.addAttribute("msg", "Upload Successful!");
        } catch (Exception e) {
            uiModel.addAttribute("msg", "Error: " + e.getMessage());
        }
        return "visualize";
    }

    // add or modify a book - updates if exists, creates if new
    @PostMapping("/update-book")
    public String updateBook(@RequestParam("id") String id, @RequestParam("title") String title, @RequestParam("author") String author, @RequestParam("genre") String genre, @RequestParam("level") String level) {
        org.apache.jena.rdf.model.Model m = jenaService.getModel();
        // create or get the book resource
        Resource bookRes = m.createResource("http://example.org/book/" + id.trim());
        Property tp = m.createProperty("http://example.org/book#title");
        Property ap = m.createProperty("http://example.org/book#author");
        Property gp = m.createProperty("http://example.org/book#genre");
        Property lp = m.createProperty("http://example.org/book#level");

        // remove old values and set new ones
        bookRes.removeAll(tp).addProperty(tp, title);
        bookRes.removeAll(ap).addProperty(ap, author);
        bookRes.removeAll(gp).addProperty(gp, genre);
        bookRes.removeAll(lp).addProperty(lp, level);

        jenaService.save();
        
        // update vector store so chatbot knows about the changes
        chatService.rebuildVectorStore();
        
        return "redirect:/books";
    }

    @GetMapping("/visualize")
    public String visualizePage() { return "visualize"; }

    // returns JSON data for the graph visualization (vis.js format)
    @GetMapping("/api/graph-data")
    @ResponseBody
    public Map<String, Object> getGraphData() {
        List<Map<String, String>> nodes = new ArrayList<>();
        List<Map<String, String>> edges = new ArrayList<>();
        Set<String> nodeIds = new HashSet<>();

        // iterate through all RDF triples
        StmtIterator it = jenaService.getModel().listStatements();
        while (it.hasNext()) {
            Statement s = it.nextStatement();
            
            // skip blank nodes (they show as random UUIDs)
            if (!s.getSubject().isURIResource()) continue;
            
            // get short names instead of full URIs
            String subj = s.getSubject().getLocalName() != null 
                ? s.getSubject().getLocalName() : s.getSubject().getURI();
            String pred = s.getPredicate().getLocalName() != null 
                ? s.getPredicate().getLocalName() : s.getPredicate().getURI();
            
            String obj;
            if (s.getObject().isLiteral()) {
                // literal value (title, genre, etc)
                obj = s.getObject().asLiteral().getString();
            } else if (s.getObject().isURIResource()) {
                obj = s.getObject().asResource().getLocalName() != null 
                    ? s.getObject().asResource().getLocalName() 
                    : s.getObject().asResource().getURI();
            } else {
                // skip blank nodes
                continue;
            }

            // add Subject Node
            if (nodeIds.add(subj)) {
                Map<String, String> node = new HashMap<>();
                node.put("id", subj);
                node.put("label", subj);
                nodes.add(node);
            }
            // add Object Node
            if (nodeIds.add(obj)) {
                Map<String, String> node = new HashMap<>();
                node.put("id", obj);
                node.put("label", obj);
                nodes.add(node);
            }
            // add Edge (The Relationship)
            Map<String, String> edge = new HashMap<>();
            edge.put("from", subj);
            edge.put("to", obj);
            edge.put("label", pred);
            edges.add(edge);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("nodes", nodes);
        result.put("edges", edges);
        return result;
    }
}