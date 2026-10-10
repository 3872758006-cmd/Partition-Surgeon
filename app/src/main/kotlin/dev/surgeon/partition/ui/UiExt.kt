package dev.surgeon.partition.ui

import android.content.Context
import android.widget.Toast
import dev.surgeon.partition.R
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

interface TitledFragment {
    fun title(context: Context): String
}

/**
 * 可滚动的长文本对话框，用于免责声明这类需要完整阅读的内容。
 *
 * @param onAgree 传了就变成"需要勾选同意"的模式：多一个确认按钮，未点同意不回调。
 */
fun showLongTextDialog(
    context: Context,
    title: String,
    text: CharSequence,
    agreeLabel: String? = null,
    onAgree: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null
) {
    val scrollView = android.widget.ScrollView(context).apply {
        val pad = (22 * context.resources.displayMetrics.density).toInt()
        setPadding(pad, pad / 2, pad, 0)
        isVerticalScrollBarEnabled = true
    }
    val textView = android.widget.TextView(context).apply {
        this.text = text
        setTextIsSelectable(true)
        textSize = 14f
        setLineSpacing(4f, 1.15f)
    }
    scrollView.addView(textView)

    val builder = MaterialAlertDialogBuilder(context)
        .setTitle(title)
        .setView(scrollView)

    if (agreeLabel != null && onAgree != null) {
        builder.setNegativeButton(context.getString(R.string.cancel)) { _, _ -> onCancel?.invoke() }
        builder.setPositiveButton(agreeLabel) { _, _ -> onAgree() }
    } else {
        builder.setPositiveButton(context.getString(R.string.ok), null)
    }
    builder.show()
}

/**
 * 统一的安全协程入口。
 *
 * 两个坑都在这里兜住：
 * 1. PreferenceFragmentCompat 的 onCreatePreferences 是在 onCreate 里调用的，
 *    此时 View 还没创建，直接取 viewLifecycleOwner 会抛 IllegalStateException —— 这是闪退的根因。
 *    所以按 view 是否存在选择 scope。
 * 2. shell / IO 里抛出的异常如果没人接，会一路冒泡把进程干掉。这里统一兜住并提示。
 */
fun Fragment.safeLaunch(
    onError: ((Throwable) -> Unit)? = null,
    block: suspend () -> Unit
) {
    val scope = if (view != null) viewLifecycleOwner.lifecycleScope else lifecycleScope
    scope.launch {
        try {
            block()
        } catch (_: CancellationException) {
            // 用户取消或页面销毁导致的正常取消，不打扰
        } catch (t: Throwable) {
            t.printStackTrace()
            if (onError != null) onError(t) else showError(t)
        }
    }
}

/** 安全取 context：Fragment 已分离或 Activity 已销毁时返回 null。 */
fun Fragment.safeContext(): Context? = context?.takeIf { activity != null && isAdded }

private fun Fragment.showError(t: Throwable) {
    val ctx = safeContext() ?: return
    val message = buildString {
        append(t.javaClass.simpleName)
        append("\n")
        append(t.message ?: getString(R.string.unknown))
    }
    runCatching {
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.error_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}

fun Fragment.toast(text: String) {
    Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show()
}

fun Fragment.toast(@StringRes res: Int) {
    Toast.makeText(requireContext(), res, Toast.LENGTH_SHORT).show()
}

fun Fragment.mainActivity(): MainActivity = requireActivity() as MainActivity

fun Fragment.navigate(fragment: Fragment, title: String) {
    mainActivity().navigate(fragment, title)
}

fun Fragment.confirm(
    title: String,
    message: String,
    positive: String,
    onYes: () -> Unit
) {
    MaterialAlertDialogBuilder(requireContext())
        .setTitle(title)
        .setMessage(message)
        .setNegativeButton(getString(R.string.cancel), null)
        .setPositiveButton(positive) { _, _ -> onYes() }
        .show()
}
