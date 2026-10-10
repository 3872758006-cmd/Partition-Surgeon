package dev.surgeon.partition.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import dev.surgeon.partition.R
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.surgeon.partition.databinding.DialogOperationBinding
import dev.surgeon.partition.util.Format
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 把一次 shell 长任务包装成 AOSP 风格的进度对话框。
 * work 正常返回即成功文案，抛异常即失败。
 */
object OperationRunner {

    interface Reporter {
        suspend fun log(message: String)
        suspend fun progress(done: Long, total: Long)
    }

    fun start(
        context: Context,
        owner: LifecycleOwner,
        title: String,
        target: String,
        work: suspend (Reporter) -> String
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_operation, null)
        val binding = DialogOperationBinding.bind(view)
        binding.tvOpTitle.text = title
        binding.tvOpTarget.text = target
        binding.pbOp.isIndeterminate = true

        var job: Job? = null
        val builder = MaterialAlertDialogBuilder(context)
        builder.setView(view)
        builder.setCancelable(false)
        builder.setNegativeButton(context.getString(R.string.cancel)) { dialog, _ ->
            job?.cancel()
            dialog.dismiss()
        }
        val dialog = builder.show()

        job = owner.lifecycleScope.launch {
            val reporter = DialogReporter(binding)
            val outcome = runCatching { work(reporter) }
            // 页面可能已经销毁，dismiss / show 都可能抛，一律吞掉
            runCatching { dialog.dismiss() }

            val error = outcome.exceptionOrNull()
            if (error is CancellationException) return@launch

            val failed = outcome.isFailure
            val message = outcome.getOrElse { it.message ?: it.toString() }
            val act = (context as? android.app.Activity)
            if (act != null && (act.isFinishing || act.isDestroyed)) return@launch
            runCatching {
                MaterialAlertDialogBuilder(context)
                    .setTitle(
                        context.getString(
                            if (failed) R.string.op_failed else R.string.op_succeeded
                        )
                    )
                    .setMessage(message)
                    .setPositiveButton(context.getString(R.string.ok), null)
                    .show()
            }
        }
    }

    private class DialogReporter(
        private val binding: DialogOperationBinding
    ) : Reporter {

        override suspend fun log(message: String) {
            withContext(Dispatchers.Main) {
                binding.tvOpLog.append(message)
                binding.tvOpLog.append("\n")
                binding.scrollOp.post { binding.scrollOp.fullScroll(View.FOCUS_DOWN) }
                Unit
            }
        }

        override suspend fun progress(done: Long, total: Long) {
            withContext(Dispatchers.Main) {
                if (total <= 0L || done < 0L) {
                    binding.pbOp.isIndeterminate = true
                    binding.tvOpPercent.text = ""
                    Unit
                } else {
                    val percent = (done * 100 / total).toInt().coerceIn(0, 100)
                    binding.pbOp.isIndeterminate = false
                    binding.pbOp.progress = percent
                    binding.tvOpPercent.text =
                        "$percent% · ${Format.size(done)} / ${Format.size(total)}"
                    Unit
                }
            }
        }
    }
}
