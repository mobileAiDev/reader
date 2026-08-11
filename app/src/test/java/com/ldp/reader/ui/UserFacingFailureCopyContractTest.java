package com.ldp.reader.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Test;

public class UserFacingFailureCopyContractTest {
    @Test
    public void bookshelfErrorsReachTheFragmentAsContextsNotThrowableText() throws IOException {
        String viewModel = readFile("src/main/java/com/ldp/reader/ui/fragment/BookShelfViewModel.kt");
        String fragment = readFile("src/main/java/com/ldp/reader/ui/fragment/BookShelfFragment.kt");

        assertTrue(viewModel.contains("MutableLiveData<BookshelfFailureContext>"));
        assertTrue(viewModel.contains("LogUtils.e(TAG,"));
        assertFalse(viewModel.contains("_errorTips.value = e.toString()"));
        assertFalse(viewModel.contains("_errorTips.value = throwable.message"));
        assertFalse(fragment.contains("showLong(error)"));
        assertTrue(fragment.contains("BookshelfFailureFeedback.messageFor(context)"));
    }

    @Test
    public void sourceImportNeverRendersExceptionDetails() throws IOException {
        String activity = readFile("src/main/java/com/ldp/reader/ui/activity/SourceImportActivity.kt");

        assertTrue(activity.contains("SourceImportUserFeedback.failureMessage(input)"));
        assertTrue(activity.contains("SourceImportUserFeedback.noUsableSourcesMessage()"));
        assertTrue(activity.contains("LogUtils.e(TAG,"));
        assertFalse(activity.contains("error.message ?: error.javaClass.simpleName"));
        assertFalse(activity.contains("binding.sourceImportResult.text = \"导入失败："));
    }

    @Test
    public void sourceImportCallsUserInputsBookSourceContentAndFiles() throws IOException {
        String activity = readFile("src/main/java/com/ldp/reader/ui/activity/SourceImportActivity.kt");
        String layout = readFile("src/main/res/layout/activity_source_import.xml");

        assertTrue(activity.contains("ToastUtils.show(\"请粘贴书源内容\")"));
        assertTrue(activity.contains("runImport(\"正在导入书源内容…\""));
        assertTrue(activity.contains("runImport(\"正在导入书源文件…\""));
        assertTrue(layout.contains("android:text=\"选择书源文件\""));
        assertTrue(layout.contains("android:text=\"粘贴书源内容\""));
        assertTrue(layout.contains("android:hint=\"粘贴书源内容\""));
        assertFalse(layout.contains("android:text=\"选择本地 JSON\""));
        assertFalse(layout.contains("android:hint=\"粘贴 JSON\""));
    }

    @Test
    public void bookDetailLocalPersistenceFailureDoesNotBlameTheNetwork() throws IOException {
        String activity = readFile("src/main/java/com/ldp/reader/ui/activity/BookDetailActivity.kt");

        assertTrue(activity.contains(
            "ToastUtils.show(BookDetailShelfFailureCopy.localPersistence())"
        ));
        assertFalse(activity.contains("加入书架失败，请检查网络"));
    }

    private static String readFile(String path) throws IOException {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }
}
