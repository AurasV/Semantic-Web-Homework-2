package app;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import jakarta.annotation.PostConstruct;
import org.apache.jena.rdf.model.*;
import org.springframework.stereotype.Service;
import java.util.stream.Collectors;

@Service
public class ChatService {
    private final JenaService jenaService;
    // vector store for embeddings - keeps them in memory
    private EmbeddingStore<TextSegment> embeddingStore = new InMemoryEmbeddingStore<>();
    // MiniLM model for generating embeddings
    private final AllMiniLmL6V2EmbeddingModel embeddingModel = new AllMiniLmL6V2EmbeddingModel();

    public ChatService(JenaService jenaService) { this.jenaService = jenaService; }

    @PostConstruct
    public void init() {
        rebuildVectorStore();
    }

    // rebuild the entire vector store from RDF data
    public void rebuildVectorStore() {
        embeddingStore = new InMemoryEmbeddingStore<>();
        Model m = jenaService.getModel();
        
        // first pass: collect book titles and user names for readable facts
        java.util.Map<String, String> bookTitles = new java.util.HashMap<>();
        java.util.Map<String, String> userNames = new java.util.HashMap<>();
        StmtIterator nameIt = m.listStatements();
        while (nameIt.hasNext()) {
            Statement s = nameIt.nextStatement();
            if (s.getObject().isLiteral()) {
                String uri = s.getSubject().getURI();
                String prop = s.getPredicate().getLocalName();
                String value = s.getObject().asLiteral().getString();
                
                if (uri.contains("/book/") && prop.equals("title")) {
                    bookTitles.put(s.getSubject().getLocalName(), value);
                } else if (uri.contains("/user/") && prop.equals("name")) {
                    userNames.put(s.getSubject().getLocalName(), value);
                }
            }
        }
        
        // second pass: build facts for both books and users (separately labeled)
        StmtIterator it = m.listStatements();
        while (it.hasNext()) {
            Statement s = it.nextStatement();
            if (s.getObject().isLiteral()) {
                String uri = s.getSubject().getURI();
                String subjectId = s.getSubject().getLocalName();
                String prop = s.getPredicate().getLocalName();
                String value = s.getObject().asLiteral().getString();
                String fact = null;
                
                if (uri.contains("/book/")) {
                    // book facts: "Book: The genre of Harry Potter is Fantasy"
                    String bookName = bookTitles.getOrDefault(subjectId, subjectId);
                    fact = "Book: The " + prop + " of " + bookName + " is " + value;
                } else if (uri.contains("/user/")) {
                    // user facts: "User: Alice prefers Science Fiction theme"
                    String userName = userNames.getOrDefault(subjectId, subjectId);
                    if (prop.equals("prefersTheme")) {
                        fact = "User: " + userName + " prefers " + value + " genre/theme";
                    } else if (prop.equals("readingLevel")) {
                        fact = "User: " + userName + " has " + value + " reading level";
                    } else if (!prop.equals("name")) {
                        fact = "User: " + userName + "'s " + prop + " is " + value;
                    }
                }
                
                if (fact != null) {
                    embeddingStore.add(embeddingModel.embed(fact).content(), TextSegment.from(fact));
                }
            }
        }
    }

    // add a single book's facts to the vector store (called when adding/editing books)
    public void addBookToVectorStore(String bookId, String title, String author, String genre, String level) {
        String[] facts = {
            "The title of " + title + " is " + title,
            "The author of " + title + " is " + author,
            "The genre of " + title + " is " + genre,
            "The level of " + title + " is " + level
        };
        for (String fact : facts) {
            embeddingStore.add(embeddingModel.embed(fact).content(), TextSegment.from(fact));
        }
    }

    public String askAi(String question) {
        try {
            // search the vector db for relevant facts (RAG approach)
            // gets top 15 most similar facts to cover more of the database
            var matches = embeddingStore.findRelevant(embeddingModel.embed(question).content(), 15);
            String context = matches.stream().map(m -> m.embedded().text()).collect(Collectors.joining("\n"));

            // using langchain4j demo endpoint (free, no API key needed)
            ChatLanguageModel model = OpenAiChatModel.builder()
                    .baseUrl("http://langchain4j.dev/demo/openai/v1")
                    .apiKey("demo")
                    .modelName("gpt-4o-mini")
                    .build();

            // RAG prompt - tells the LLM to ONLY use our data, not its own knowledge
            String prompt = "You are a helpful book recommendation assistant for a library system. " +
                    "Answer questions using ONLY the information provided in the context below. " +
                    "Do NOT use any external knowledge about books.\n\n" +
                    "The context contains:\n" +
                    "- BOOK facts: titles, genres, authors, and reading levels of books in our database\n" +
                    "- USER facts: user preferences (preferred genres and reading levels)\n\n" +
                    "When someone asks for a recommendation:\n" +
                    "- If they mention a genre they like (e.g. 'I like Fantasy'), recommend books with matching genres from the context\n" +
                    "- If they ask about a specific user (e.g. 'What would Alice like?'), match that user's preferences to books\n" +
                    "Context from database:\n" + context + "\n\n" +
                    "Question: " + question + "\n\n" +
                    "Answer:";

            return model.generate(prompt);
        } catch (Exception e) {
            return "Bot Error: I'm having trouble connecting to the AI. " + e.getMessage();
        }
    }
}