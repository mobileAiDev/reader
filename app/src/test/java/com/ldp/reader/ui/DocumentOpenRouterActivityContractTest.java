package com.ldp.reader.ui;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class DocumentOpenRouterActivityContractTest {

    @Test
    public void textImportRunsOnIoBeforeOpeningReader() throws IOException {
        String activity = readFile("src/main/java/com/ldp/reader/ui/activity/DocumentOpenRouterActivity.kt");

        assertTrue(activity.contains("CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)"));
        assertTrue(activity.contains("ReaderDocumentFormat.TEXT -> {"));
        assertTrue(activity.contains("importText(uri)"));
        assertTrue(activity.contains("withContext(Dispatchers.IO)"));
        assertTrue(activity.contains("LocalTextImportStore.importUri(applicationContext, uri)"));
        assertTrue(activity.contains("importResult.book"));
        assertTrue(activity.contains("ReadActivity.startActivity("));
        assertTrue(activity.contains("importScope.cancel()"));
    }

    @Test
    public void emptyTextShowsReadableFeedbackBeforeAnyShelfWriteOrReaderLaunch() throws IOException {
        String activity = readFile("src/main/java/com/ldp/reader/ui/activity/DocumentOpenRouterActivity.kt");
        String store = readFile("src/main/java/com/ldp/reader/document/LocalTextImportStore.kt");
        String result = readFile("src/main/java/com/ldp/reader/document/LocalTextImportResult.kt");

        int emptyResult = store.indexOf(
            "LocalTextImportWriteResult.Empty -> return LocalTextImportResult.Empty"
        );
        int repositoryWritePath = store.indexOf("val repository = BookRepository.getInstance()");
        int successLaunch = activity.indexOf("is LocalTextImportResult.Success ->");
        int feedback = activity.indexOf(
            "else -> ToastUtils.show(LocalTextImportUserMessage.forResult(importResult))"
        );

        assertTrue(emptyResult > 0);
        assertTrue(repositoryWritePath > emptyResult);
        assertTrue(successLaunch > 0);
        assertTrue(feedback > successLaunch);
        assertTrue(result.contains("LocalTextImportResult.Empty -> \"文件中没有可阅读内容\""));
    }

    @Test
    public void pathologicalSingleLineStopsBeforeShelfWriteAndShowsReadableFeedback() throws IOException {
        String store = readFile("src/main/java/com/ldp/reader/document/LocalTextImportStore.kt");
        String result = readFile("src/main/java/com/ldp/reader/document/LocalTextImportResult.kt");

        int lineLimitResult = store.indexOf(
            "LocalTextImportWriteResult.LineTooLong -> return LocalTextImportResult.LineTooLong"
        );
        int repositoryWritePath = store.indexOf("val repository = BookRepository.getInstance()");

        assertTrue(lineLimitResult > 0);
        assertTrue(repositoryWritePath > lineLimitResult);
        assertTrue(result.contains(
            "LocalTextImportResult.LineTooLong -> \"文件中有一段内容过长，暂时无法打开\""
        ));
    }

    @Test
    public void cancellationIsRethrownInsteadOfShowingImportFailureAfterDestroy() throws IOException {
        String activity = readFile("src/main/java/com/ldp/reader/ui/activity/DocumentOpenRouterActivity.kt");

        assertTrue(activity.contains("runInterruptible(Dispatchers.IO)"));
        assertTrue(activity.contains("catch (error: CancellationException)"));
        assertTrue(activity.contains("throw error"));
        assertTrue(activity.contains("currentCoroutineContext().ensureActive()"));
    }

    @Test
    public void providerFailureCannotRouteUsingAnUntrustedPathSegment() throws IOException {
        String fileName = readFile("src/main/java/com/ldp/reader/document/DocumentFileName.kt");
        String activity = readFile("src/main/java/com/ldp/reader/ui/activity/DocumentOpenRouterActivity.kt");

        assertFalse(fileName.contains("lastPathSegment"));
        assertTrue(fileName.contains("catch (error: CancellationException)"));
        assertTrue(fileName.contains("catch (_: SecurityException)"));
        assertTrue(fileName.contains("catch (_: RuntimeException)"));
        assertTrue(activity.contains("if (extension.isBlank())"));
        assertTrue(activity.contains("ToastUtils.show(\"无法识别文件格式\")"));
    }

    @Test
    public void slowDocumentRoutingShowsAVisibleCancelableProgressPage() throws IOException {
        String activity = readFile("src/main/java/com/ldp/reader/ui/activity/DocumentOpenRouterActivity.kt");
        String layout = readFile("src/main/res/layout/activity_document_open_router.xml");

        assertTrue(activity.contains("setContentView(R.layout.activity_document_open_router)"));
        assertTrue(layout.contains("<ProgressBar"));
        assertTrue(layout.contains("正在打开文件…"));
        assertTrue(activity.contains("override fun onDestroy()"));
        assertTrue(activity.contains("importScope.cancel()"));
    }

    @Test
    public void safTextKeepsItsSourceReferenceAndWriteGrantForOptionalDeletion() throws IOException {
        String activity = readFile("src/main/java/com/ldp/reader/ui/activity/DocumentOpenRouterActivity.kt");
        String importStore = readFile("src/main/java/com/ldp/reader/document/LocalTextImportStore.kt");

        assertTrue(activity.contains("Intent.FLAG_GRANT_WRITE_URI_PERMISSION"));
        assertTrue(activity.contains("takePersistableUriPermission("));
        assertTrue(importStore.contains("LocalTextSourceReferencePersistence.store.save("));
        assertTrue(importStore.contains("collBook.get_id()"));
        assertTrue(importStore.contains("uri.toString()"));
    }

    private static String readFile(String relativePath) throws IOException {
        return new String(
            Files.readAllBytes(new File(relativePath).toPath()),
            StandardCharsets.UTF_8
        );
    }
}
