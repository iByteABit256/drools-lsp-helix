package org.drools.lsp.server;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.services.LanguageClient;

public class TestHelperMethods {

    private static final long PUBLISH_TIMEOUT_MS = 5000;

    private TestHelperMethods() {
    }

    public static DroolsLspDocumentService getDroolsLspDocumentService(String drl) {
        return getDroolsLspServerForDocument(drl, new ArrayList<>(), new AtomicBoolean()).getTextDocumentService();
    }

    /**
     * Opens the given DRL document and returns the diagnostics published asynchronously by the server.
     */
    public static List<Diagnostic> getPublishedDiagnostics(String drl) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        AtomicBoolean published = new AtomicBoolean(false);
        getDroolsLspServerForDocument(drl, diagnostics, published);
        awaitPublished(published);
        return diagnostics;
    }

    private static void awaitPublished(AtomicBoolean published) {
        long deadline = System.currentTimeMillis() + PUBLISH_TIMEOUT_MS;
        while (!published.get() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private static DroolsLspServer getDroolsLspServerForDocument(String drl, List<Diagnostic> diagnostics, AtomicBoolean published) {
        DroolsLspServer ls = new DroolsLspServer();
        ls.connect(new LanguageClient() {
            @Override
            public void telemetryEvent(Object object) {
            }

            @Override
            public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams requestParams) {
                return null;
            }

            @Override
            public void showMessage(MessageParams messageParams) {
            }

            @Override
            public void publishDiagnostics(PublishDiagnosticsParams d) {
                diagnostics.clear();
                diagnostics.addAll(d.getDiagnostics());
                published.set(true);
            }

            @Override
            public void logMessage(MessageParams message) {
            }
        });

        TextDocumentItem doc = new TextDocumentItem();
        doc.setUri("myDocument");
        doc.setText(drl);
        ls.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(doc));
        return ls;
    }
}
