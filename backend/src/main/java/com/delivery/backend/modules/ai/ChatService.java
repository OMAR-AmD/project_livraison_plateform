package com.delivery.backend.modules.ai;

import com.delivery.backend.modules.auth.User;
import com.delivery.backend.modules.delivery.DeliveryService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The customer-facing assistant: local RAG over the FAQ plus the caller's live
 * order state, served by a locally hosted language model.
 *
 * <p><strong>The assistant is optional and must never prevent the platform from
 * starting.</strong> Both the embedding model and the chat model live in Ollama.
 * When Ollama is not running, the retrieval corpus cannot be built and the model
 * cannot be called, so this service degrades: the delivery platform still boots,
 * still books, prices, dispatches and tracks, and only the chat feature reports
 * itself unavailable.
 *
 * <p>That behaviour is deliberate. Previously the corpus was loaded in a
 * {@code @PostConstruct} that was allowed to throw, which meant a missing local
 * LLM took down the entire Spring context — every endpoint 500'd because a
 * delivery platform could not deliver anything without a chatbot. The failure is
 * now captured into {@link #retrievalReady} and reported per-request.
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    /** Returned to the client when the local model stack is not available. */
    static final String UNAVAILABLE_MESSAGE =
            "The assistant is currently unavailable because the local language model is not "
                    + "responding. Everything else in the platform still works — start Ollama and "
                    + "pull the llama3.1:8b and nomic-embed-text models to enable chat.";

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final DeliveryService deliveryService;
    private final JdbcTemplate jdbcTemplate;

    /** False when the FAQ could not be embedded because Ollama was unreachable. */
    private volatile boolean retrievalReady = false;

    @Value("classpath:faq.txt")
    private Resource faqResource;

    @Value("${spring.ai.vectorstore.pgvector.table-name:vector_store}")
    private String tableName;

    @Value("${spring.ai.vectorstore.pgvector.dimensions:768}")
    private int dimensions;

    public ChatService(ChatClient.Builder chatClientBuilder,
                       VectorStore vectorStore,
                       DeliveryService deliveryService,
                       JdbcTemplate jdbcTemplate) {
        this.chatClient = chatClientBuilder.build();
        this.vectorStore = vectorStore;
        this.deliveryService = deliveryService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Creates the retrieval table if it is absent, and replaces it if an older,
     * incompatible one is in the way.
     *
     * <p>Spring AI can do this itself via {@code initialize-schema}, but it does
     * so while the application context is starting, where a failure is fatal.
     * The pgvector extension has to be installed by a superuser, which a real
     * deployment's application user frequently is not, so this is a realistic
     * failure rather than a theoretical one. Doing it here means a missing
     * extension disables the assistant instead of preventing the platform from
     * starting at all.
     *
     * <p>{@code CREATE TABLE IF NOT EXISTS} is not enough on its own: an earlier
     * build of this project left a {@code vector_store} table holding
     * {@code metadata json} and no {@code embedding} column at all, which the
     * {@code IF NOT EXISTS} check happily skips and every later query then fails
     * on. The table is a derived cache of faq.txt that is rebuilt on every
     * start, so discarding an incompatible one costs nothing.
     */
    private void ensureSchema() {
        Boolean tableExists = jdbcTemplate.queryForObject("""
                SELECT to_regclass(?) IS NOT NULL
                """, Boolean.class, tableName);

        if (Boolean.TRUE.equals(tableExists)) {
            Integer columns = jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM information_schema.columns
                    WHERE table_name = ? AND column_name = 'embedding'
                    """, Integer.class, tableName);

            if (columns != null && columns == 0) {
                log.warn("Table {} exists but has no embedding column; recreating it", tableName);
                jdbcTemplate.execute("DROP TABLE IF EXISTS " + tableName);
            }
        }

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    id uuid PRIMARY KEY,
                    content text,
                    metadata jsonb,
                    embedding vector(%d)
                )
                """.formatted(tableName, dimensions));
    }

    /**
     * Builds the retrieval corpus at startup.
     *
     * <p>Never throws. The FAQ is the entire corpus and is only a few lines, so it
     * is rebuilt on every start: an "only load when the store is empty" check
     * would be a trap, because if the source text is edited or translated while
     * the store already holds chunks, the stale version keeps being served
     * alongside the new one.
     */
    @PostConstruct
    public void initVectorStore() {
        try {
            // Must happen before any query. If the pgvector extension is
            // missing this throws and the catch below disables the assistant.
            ensureSchema();

            // topK is deliberately oversized: a low value would clear only part of
            // the store, leaving stale chunks to be retrieved with the new ones.
            SearchRequest request = SearchRequest.query("delivery policy refund payment")
                    .withTopK(500)
                    .withSimilarityThresholdAll();
            List<Document> existing = vectorStore.similaritySearch(request);
            if (!existing.isEmpty()) {
                vectorStore.delete(existing.stream().map(Document::getId).toList());
                log.info("Cleared {} stale FAQ chunks from the vector store", existing.size());
            }

            TextReader textReader = new TextReader(faqResource);
            List<Document> documents = textReader.get();
            TokenTextSplitter splitter = new TokenTextSplitter();
            vectorStore.add(splitter.apply(documents));

            retrievalReady = true;
            log.info("FAQ loaded into the vector store ({} documents). Assistant is ready.", documents.size());

        } catch (Exception e) {
            retrievalReady = false;
            log.warn("""

                    ================================================================
                     The AI assistant is DISABLED and the rest of the platform will run normally.
                     Cause: {}: {}
                     To enable it, start Ollama and pull the required models:
                       ollama serve
                       ollama pull llama3.1:8b
                       ollama pull nomic-embed-text
                    ================================================================
                    """,
                    e.getClass().getSimpleName(), e.getMessage());
        }
    }

    /** True when the local model stack answered during startup. */
    public boolean isReady() {
        return retrievalReady;
    }

    public String chatWithClient(String question, User client) {
        if (!retrievalReady) {
            return UNAVAILABLE_MESSAGE;
        }

        String faqContext;
        try {
            faqContext = vectorStore.similaritySearch(question).stream()
                    .map(Document::getContent)
                    .collect(Collectors.joining("\n"));
        } catch (Exception e) {
            log.warn("Retrieval failed mid-request: {}", e.getMessage());
            return UNAVAILABLE_MESSAGE;
        }

        // Live order state, so the assistant can answer about the caller's actual
        // deliveries rather than a generic policy answer.
        String deliveriesContext = deliveryService.getDeliveriesForClient(client).stream()
                .filter(d -> d.getStatus() != com.delivery.backend.modules.delivery.DeliveryStatus.DELIVERED)
                .filter(d -> d.getStatus() != com.delivery.backend.modules.delivery.DeliveryStatus.CANCELLED)
                .map(d -> "- Order " + d.getId()
                        + " | pickup: " + d.getPickupAddress()
                        + " | drop-off: " + d.getDropoffAddress()
                        + " | status: " + d.getStatus()
                        + " | price: " + d.getPrice() + " MAD"
                        + " | payment: " + d.getPaymentStatus())
                .collect(Collectors.joining("\n", "Orders:\n", ""));

        if (deliveriesContext.startsWith("Orders:\n") && deliveriesContext.length() == 8) {
            deliveriesContext = "No active orders.";
        }

        String systemMessage = new PromptTemplate("""
                You are the virtual assistant of SwiftDeliver, a last-mile delivery company.
                Your job is to help the customer politely, concisely and professionally in English.

                Here is the official documentation (FAQ) you may draw on:
                {faq}

                Here are the customer's currently active orders:
                {deliveries}

                Rules:
                1. Always answer in English.
                2. When referring to an order, always use the exact ID shown above; never guess or invent one.
                3. If the customer asks to cancel an order, call the 'cancelDeliveryFunction' tool with the ID of
                   one of THEIR OWN orders. The tool refuses anything else - do not retry with a different ID.
                4. If you cannot answer from the documentation or the order list, say so politely.
                5. Keep answers short. This is a customer support chat, not a document.
                """).render(Map.of("faq", faqContext, "deliveries", deliveriesContext));

        try {
            return chatClient.prompt()
                    .system(systemMessage)
                    .user(question)
                    .functions("cancelDeliveryFunction")
                    .call()
                    .content();
        } catch (Exception e) {
            // Ollama may have gone away after a successful startup.
            log.warn("Chat completion failed: {}: {}", e.getClass().getSimpleName(), e.getMessage());
            return UNAVAILABLE_MESSAGE;
        }
    }
}
