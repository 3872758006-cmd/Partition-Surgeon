package dev.surgeon.partition.ui

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import dev.surgeon.partition.BuildConfig
import dev.surgeon.partition.R
import dev.surgeon.partition.core.RootManager
import dev.surgeon.partition.core.shell.RootShell
import dev.surgeon.partition.databinding.ActivityMainBinding
import dev.surgeon.partition.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (supportFragmentManager.backStackEntryCount > 0) {
                    supportFragmentManager.popBackStack()
                    updateTitle()
                } else {
                    finish()
                }
            }
        })
        supportFragmentManager.addOnBackStackChangedListener { updateTitle() }

        if (savedInstanceState == null) {
            if (Prefs.disclaimerAgreed(this)) {
                checkRoot()
            } else {
                showDisclaimer()
            }
        } else {
            updateTitle()
        }
    }

    /** 首次启动强制阅读免责声明，不同意就退出。 */
    private fun showDisclaimer() {
        showLongTextDialog(
            this,
            getString(R.string.set_disclaimer_title),
            getText(R.string.disclaimer_body),
            agreeLabel = getString(R.string.disclaimer_agree),
            onAgree = {
                Prefs.setDisclaimerAgreed(this, true)
                checkRoot()
            },
            onCancel = { finish() }
        )
    }

    /** Root 门禁：拿不到 root shell 就不进入任何功能页。 */
    fun checkRoot() {
        lifecycleScope.launch {
            val info = runCatching {
                withContext(Dispatchers.IO) { RootManager.probe(force = true) }
            }.getOrElse {
                RootManager.RootInfo(false, -1, RootManager.Impl.UNKNOWN, "", "", 0)
            }

            if (info.granted) {
                RootShell.reset()
                SurgeonRepository.invalidate()
                val ok = runCatching {
                    val home = HomeFragment()
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.container, home)
                        .commitAllowingStateLoss()
                    supportActionBar?.title = home.title(this@MainActivity)
                }.isSuccess
                if (ok) return@launch
                showNoRoot(getString(R.string.err_generic, "首页初始化失败"))
            } else {
                showNoRoot(info.display)
            }
        }
    }

    private fun showNoRoot(state: String) {
        runCatching {
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, NoRootFragment.newInstance(state))
                .commitAllowingStateLoss()
            supportActionBar?.title = getString(R.string.app_name)
        }
    }

    fun navigate(fragment: Fragment, title: String) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, fragment)
            .addToBackStack(title)
            .commit()
        supportActionBar?.title = title
    }

    private fun updateTitle() {
        val current = supportFragmentManager.findFragmentById(R.id.container) as? TitledFragment
        supportActionBar?.title = current?.title(this) ?: getString(R.string.app_name)
    }

    fun versionName(): String = buildString {
        append(BuildConfig.VERSION_NAME)
        if (BuildConfig.DEBUG) append(" (debug)")
    }
}
