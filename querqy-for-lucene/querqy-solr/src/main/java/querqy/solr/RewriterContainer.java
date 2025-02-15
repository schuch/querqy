package querqy.solr;

import org.apache.solr.common.SolrException;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.core.CloseHook;
import org.apache.solr.core.SolrCore;
import org.apache.solr.core.SolrResourceLoader;
import org.apache.solr.search.SolrIndexSearcher;
import org.apache.solr.util.RefCounted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import querqy.lucene.rewrite.infologging.Sink;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public abstract class RewriterContainer<R extends SolrResourceLoader> {

    protected final Logger LOG = LoggerFactory.getLogger(getClass());

    protected Map<String, RewriterFactoryContext> rewriters = new HashMap<>();
    protected R resourceLoader;
    protected SolrCore core;
    private RewritersChangeListener rewritersChangeListener = null;
    protected final Map<String, Sink> infoLoggingSinks;

    public interface RewritersChangeListener {
        void rewritersChanged(SolrIndexSearcher indexSearcher, Set<RewriterFactoryContext> allRewriters);
    }

    protected RewriterContainer(final SolrCore core, final R resourceLoader, final Map<String, Sink> infoLoggingSinks) {
        if (core.getResourceLoader() != resourceLoader) {
            throw new IllegalArgumentException("ResourceLoader doesn't belong to this SolrCore");
        }
        this.core = core;
        this.resourceLoader = resourceLoader;
        this.infoLoggingSinks = infoLoggingSinks;
        this.core.addCloseHook(new CloseHook(){

            /**
             * (1) Is called before any component is closed. To guarantee consistency,
             * we keep the container alive as long as the QuerqyRewriterRequestHandler
             * is not closed
             */
            @Override
            public void preClose(SolrCore core) {
                // noop
            }

            /**
             * (2) the QuerqyRewriterRequestHandler is closed
             *
             * (3) the SolrCore is closed
             *
             * (4) We are going to close the RewriterContainer
             */
            @Override
            public void postClose(SolrCore core) {
                close();
            }

        });
    }

    protected abstract void init(@SuppressWarnings({"rawtypes"}) NamedList args);

    /**
     * Close hook that will be triggered on close.
     */
    protected abstract void doClose();

    protected abstract void doSaveRewriter(final String rewriterId, final Map<String, Object> instanceDescription)
            throws IOException;
    protected abstract void deleteRewriter(final String rewriterId) throws IOException;

    /**
     * The rewriter description is used for the rest endpoints to get detailed information to the rewriter chains.
     * @param rewriterId The rewriter ID
     * @return The rewriter definition
     * @throws IOException If the rewriter definition cannot be read
     */
    public abstract Map<String, Object> readRewriterDefinition(String rewriterId) throws IOException;

    public void saveRewriter(final String rewriterId, final Map<String, Object> instanceDescription)
            throws IOException {

        validateRewriterDescription(rewriterId, instanceDescription);
        doSaveRewriter(rewriterId, instanceDescription);

    }

    public Optional<RewriterFactoryContext> getRewriterFactory(final String rewriterId) {
        return Optional.ofNullable(rewriters.get(rewriterId));
    }

    public synchronized Collection<RewriterFactoryContext> getRewriterFactories(final RewritersChangeListener listener) {
        this.rewritersChangeListener = listener;
        return rewriters.values();
    }

    public final synchronized void close() {
        doClose();
        rewritersChangeListener = null;
        resourceLoader = null;
        core = null;
        rewriters = null;
    }

    protected synchronized void loadRewriter(final String rewriterId, final Map<String, Object> instanceDesc) {

        final SolrRewriterFactoryAdapter factoryLoader = SolrRewriterFactoryAdapter.loadInstance(rewriterId,
                instanceDesc);
        factoryLoader.configure((Map<String, Object>) instanceDesc.getOrDefault("config", Collections.emptyMap()));

        final Map<String, RewriterFactoryContext> newRewriters = new HashMap<>(rewriters);
        newRewriters.put(
                rewriterId,
                new RewriterFactoryContext(
                        factoryLoader.getRewriterFactory(),
                        getLoggingSinksFromInstanceDescription(instanceDesc)
                )
        );
        rewriters = newRewriters;
        LOG.info("Loaded rewriter: {}", rewriterId);

    }

    protected List<Sink> getLoggingSinksFromInstanceDescription(final Map<String, Object> instanceDescription) {
        final Map<String, Map<String, ?>> infoLoggingDesc = (Map<String, Map<String, ?>>)
                instanceDescription.get("info_logging");
        if (infoLoggingDesc != null) {
            final List<String> sinkNames = readSinkNamesFromInstanceDescription(instanceDescription);
            if (!sinkNames.isEmpty()) {
                return sinkNames.stream().map(name -> {
                    final Sink sink = infoLoggingSinks.get(name);
                    if (sink == null) {
                        throw new IllegalArgumentException("No such info logging sink: " + name);
                    }
                    return sink;
                }).collect(Collectors.toList());
            }


        }

        return Collections.emptyList();

    }

    protected List<String> readSinkNamesFromInstanceDescription(final Map<String, Object> instanceDescription) {
        final Map<String, Map<String, ?>> infoLoggingDesc = (Map<String, Map<String, ?>>)
                instanceDescription.get("info_logging");
        if (infoLoggingDesc != null) {
            final List<String> sinkNames = (List<String>) infoLoggingDesc.get("sinks");
            if (sinkNames != null) {
                return sinkNames;
            }
        }
        return Collections.emptyList();
    }


    protected synchronized void notifyRewritersChangeListener() {

        if (rewritersChangeListener != null && !rewriters.isEmpty()) {

            // We must not call lister.rewritersChanges() asynchronously. If we did, we might happen to decref and
            // possibly let the core close the searcher prematurely
            final RefCounted<SolrIndexSearcher> refCounted = core.getSearcher();
            try {
                rewritersChangeListener.rewritersChanged(refCounted.get(), new HashSet<>(rewriters.values()));
            } finally {
                refCounted.decref();
            }
        }
    }

    private void validateRewriterDescription(final String rewriterId, final Map<String, Object> instanceDescription) {

        final SolrRewriterFactoryAdapter factoryLoader = SolrRewriterFactoryAdapter.loadInstance(rewriterId,
                instanceDescription);
        List<String> errors = factoryLoader.validateConfiguration(
                (Map<String, Object>) instanceDescription.getOrDefault("config", Collections.emptyMap()));

        final List<String> sinkNames = readSinkNamesFromInstanceDescription(instanceDescription);
        final List<String> missingSinks = sinkNames.stream().filter(sinkName -> !infoLoggingSinks.containsKey(sinkName))
                .collect(Collectors.toList());

        if (!missingSinks.isEmpty()) {
            if (errors == null) {
                errors = new ArrayList<>(1);
            }
            errors.add("One or more infoLogging sinks do not exist: " + String.join(", ", missingSinks));
        }

        if (errors != null && !errors.isEmpty()) {
            throw new SolrException(SolrException.ErrorCode.BAD_REQUEST,
                    "Invalid configuration for rewriter " + rewriterId + " " + String.join("; ", errors));
        }

    }


}
