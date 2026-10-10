package dev.surgeon.partition.ui

import android.content.Context
import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.surgeon.partition.R
import dev.surgeon.partition.core.shell.RootShell
import dev.surgeon.partition.engine.FlashEngine
import dev.surgeon.partition.engine.ImageProbe
import dev.surgeon.partition.engine.OpLog
import dev.surgeon.partition.model.OpLogEntry
import dev.surgeon.partition.model.PartitionInfo
import dev.surgeon.partition.safety.SafetyPolicy
import dev.surgeon.partition.util.Format
import dev.surgeon.partition.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class ImageRef(val path: String, val sizeBytes: Long)

/** 输出目录下所有镜像的列表。 */
class ImageLibraryFragment : PreferenceFragmentCompat(), TitledFragment {

    override fun title(context: Context): String = context.getString(R.string.img_lib_title)

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
        load()
    }

    override fun onResume() {
        super.onResume()
        if (isAdded && view != null) load()
    }

    private fun load() {
        safeLaunch {
            val context = requireContext()
            val images = withContext(Dispatchers.IO) {
                ImageProbe.listImages(RootShell.shell(), Prefs.outputDir(context))
            }
            val screen = preferenceScreen ?: return@safeLaunch
            screen.removeAll()

            if (images.isEmpty()) {
                screen.addPreference(
                    Preference(context).apply {
                        setTitle(R.string.img_lib_none)
                        isSelectable = false
                    }
                )
                return@safeLaunch
            }

            val total = images.sumOf { it.first }
            val cat = PreferenceCategory(context).apply {
                title = getString(R.string.img_lib_summary_all, images.size, Format.size(total))
            }
            screen.addPreference(cat)

            images.forEach { (size, path) ->
                cat.addPreference(
                    Preference(context).apply {
                        title = path.substringAfterLast('/')
                        summary = buildString {
                            append(Format.size(size))
                            append("\n")
                            append(path.substringBeforeLast('/'))
                        }
                        setIcon(R.drawable.ic_image)
                        setOnPreferenceClickListener {
                            navigate(
                                ImageDetailFragment.newInstance(path, size),
                                path.substringAfterLast('/')
                            )
                            true
                        }
                    }
                )
            }
        }
    }
}

class ImageDetailFragment : PreferenceFragmentCompat(), TitledFragment {

    private var ref: ImageRef? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = arguments?.getString(ARG_PATH).orEmpty()
        val size = arguments?.getLong(ARG_SIZE) ?: 0L
        ref = if (path.isNotEmpty()) ImageRef(path, size) else null
    }

    override fun title(context: Context): String =
        ref?.path?.substringAfterLast('/') ?: context.getString(R.string.img_detail_title)

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
        load()
    }

    private fun load() {
        val image = ref ?: return
        safeLaunch {
            val info = withContext(Dispatchers.IO) {
                ImageProbe.inspect(RootShell.shell(), image.path)
            }
            build(image, info)
        }
    }

    private fun build(image: ImageRef, info: ImageProbe.ImageInfo) {
        val context = requireContext()
        val screen = preferenceManager.createPreferenceScreen(context)

        val cat = PreferenceCategory(context).apply { setTitle(R.string.img_cat_info) }
        screen.addPreference(cat)
        cat.addPreference(readonly(context, R.string.img_path, info.path))
        cat.addPreference(readonly(context, R.string.img_size, Format.size(info.sizeBytes)))
        cat.addPreference(readonly(context, R.string.img_kind, info.kind))
        cat.addPreference(
            readonly(
                context,
                R.string.img_magic,
                info.magic.ifBlank { getString(R.string.unknown) }
            )
        )

        val ops = PreferenceCategory(context).apply { setTitle(R.string.img_cat_ops) }
        screen.addPreference(ops)
        ops.addPreference(
            Preference(context).apply {
                setTitle(R.string.img_action_flash_to)
                setSummary(R.string.img_action_flash_to_summary)
                setIcon(R.drawable.ic_flash)
                setOnPreferenceClickListener { pickPartition(image); true }
            }
        )
        ops.addPreference(
            Preference(context).apply {
                setTitle(R.string.img_action_sha)
                setIcon(R.drawable.ic_shield)
                setOnPreferenceClickListener { hash(image); true }
            }
        )
        ops.addPreference(
            Preference(context).apply {
                setTitle(R.string.img_action_delete)
                setSummary(R.string.img_action_delete_summary)
                setIcon(R.drawable.ic_warning)
                setOnPreferenceClickListener { confirmDelete(image); true }
            }
        )

        preferenceScreen = screen
    }

    private fun pickPartition(image: ImageRef) {
        safeLaunch {
            val context = requireContext()
            val expert = Prefs.expertMode(context)
            val targets = SurgeonRepository.partitions()
                .filter { SafetyPolicy.canFlash(it, expert) }
                .sortedBy { it.name }
            if (targets.isEmpty()) {
                toast(R.string.empty_hint)
                return@safeLaunch
            }
            val labels = targets.map {
                "${it.name} · ${Format.size(it.sizeBytes)}"
            }.toTypedArray()
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.flash_pick_partition)
                .setItems(labels) { _, which ->
                    confirmFlash(targets[which], image)
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        }
    }

    private fun confirmFlash(partition: PartitionInfo, image: ImageRef) {
        val context = requireContext()
        val message = buildString {
            append(
                getString(
                    R.string.flash_warn_body,
                    image.path,
                    Format.size(image.sizeBytes),
                    partition.name
                )
            )
            if (partition.sizeBytes > 0 && image.sizeBytes > partition.sizeBytes) {
                append("\n\n").append(
                    getString(
                        R.string.img_warn_size,
                        Format.size(image.sizeBytes),
                        Format.size(partition.sizeBytes)
                    )
                )
            }
        }
        confirm(
            getString(R.string.flash_warn_title),
            message,
            getString(R.string.flash_check_backup)
        ) { runFlash(partition, image) }
    }

    private fun runFlash(partition: PartitionInfo, image: ImageRef) {
        val context = requireContext()
        val verify = Prefs.verifyAfterFlash(context)
        OperationRunner.start(
            context,
            viewLifecycleOwner,
            getString(R.string.op_flashing),
            partition.name
        ) { reporter ->
            withContext(Dispatchers.IO) {
                reporter.log(getString(R.string.op_writing))
                val size = FlashEngine.flash(partition, image.path, RootShell.shell()) { d, t ->
                    reporter.progress(d, t)
                }
                if (verify) {
                    reporter.log(getString(R.string.op_verifying))
                    if (!FlashEngine.verify(RootShell.shell(), partition, image.path, size)) {
                        throw IllegalStateException("回读校验不一致")
                    }
                }
                OpLog.add(
                    context,
                    OpLogEntry(
                        System.currentTimeMillis(), "刷入", partition.name, true,
                        "${image.path.substringAfterLast('/')} · ${Format.size(size)}"
                    )
                )
                getString(R.string.result_flash_ok, partition.name, Format.size(size))
            }
        }
    }

    private fun hash(image: ImageRef) {
        val context = requireContext()
        OperationRunner.start(
            context,
            viewLifecycleOwner,
            getString(R.string.op_hashing),
            image.path.substringAfterLast('/')
        ) { reporter ->
            withContext(Dispatchers.IO) {
                reporter.progress(0, 1)
                val sha = ImageProbe.sha256(RootShell.shell(), image.path)
                reporter.progress(1, 1)
                getString(R.string.result_hash, sha)
            }
        }
    }

    private fun confirmDelete(image: ImageRef) {
        val context = requireContext()
        confirm(
            getString(R.string.img_delete_confirm_title),
            getString(R.string.img_delete_confirm_body, image.path),
            getString(R.string.rp_action_delete)
        ) {
            safeLaunch {
                withContext(Dispatchers.IO) {
                    RootShell.shell().exec("rm -f \"${image.path}\"")
                }
                OpLog.add(
                    context,
                    OpLogEntry(
                        System.currentTimeMillis(), "删除镜像",
                        image.path.substringAfterLast('/'), true, Format.size(image.sizeBytes)
                    )
                )
                toast(R.string.img_deleted)
                parentFragmentManager.popBackStack()
            }
        }
    }

    private fun readonly(context: Context, titleRes: Int, value: String): Preference =
        Preference(context).apply {
            setTitle(titleRes)
            summary = value
            isSelectable = false
            isPersistent = false
        }

    companion object {
        private const val ARG_PATH = "path"
        private const val ARG_SIZE = "size"

        fun newInstance(path: String, sizeBytes: Long): ImageDetailFragment =
            ImageDetailFragment().apply {
                arguments = bundleOf(ARG_PATH to path, ARG_SIZE to sizeBytes)
            }
    }
}
