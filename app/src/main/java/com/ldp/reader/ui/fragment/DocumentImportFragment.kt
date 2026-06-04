package com.ldp.reader.ui.fragment

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.ldp.reader.databinding.FragmentDocumentImportBinding
import com.ldp.reader.ui.activity.DocumentOpenRouterActivity

class DocumentImportFragment : Fragment() {
    private var binding: FragmentDocumentImportBinding? = null
    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            DocumentOpenRouterActivity.start(requireContext(), uri)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val viewBinding = FragmentDocumentImportBinding.inflate(inflater, container, false)
        binding = viewBinding
        return viewBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding?.documentImportOpen?.setOnClickListener {
            openDocument.launch(
                arrayOf(
                    "text/plain",
                    "application/epub+zip",
                    "application/pdf",
                    "application/zip",
                    "application/x-cbz",
                    "application/octet-stream"
                )
            )
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
