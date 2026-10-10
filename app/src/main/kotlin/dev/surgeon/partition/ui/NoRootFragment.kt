package dev.surgeon.partition.ui

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import dev.surgeon.partition.R
import dev.surgeon.partition.databinding.FragmentNoRootBinding

class NoRootFragment : Fragment(R.layout.fragment_no_root) {

    private var _binding: FragmentNoRootBinding? = null
    private val binding get() = _binding!!

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentNoRootBinding.bind(view)
        val state = arguments?.getString(KEY_STATE).orEmpty()
        binding.tvNoRootState.text = getString(R.string.no_root_hint, state)
        binding.btnRetryRoot.setOnClickListener {
            (requireActivity() as MainActivity).checkRoot()
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val KEY_STATE = "state"

        fun newInstance(state: String): NoRootFragment =
            NoRootFragment().apply {
                arguments = Bundle().apply { putString(KEY_STATE, state) }
            }
    }
}
