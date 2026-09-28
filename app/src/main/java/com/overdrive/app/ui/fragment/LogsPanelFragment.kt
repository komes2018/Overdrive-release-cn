package com.overdrive.app.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.Spinner
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.overdrive.app.ui.adapter.LogsAdapter
import com.overdrive.app.ui.viewmodel.LogsViewModel
import com.overdrive.app.ui.viewmodel.MainViewModel
import com.overdrive.app.R

/**
 * Persistent logs panel fragment that appears at the bottom (portrait) or left (landscape).
 */
class LogsPanelFragment : Fragment() {
    
    private val logsViewModel: LogsViewModel by activityViewModels()
    private val mainViewModel: MainViewModel by activityViewModels()
    
    private lateinit var recyclerLogs: RecyclerView
    private lateinit var btnExpandCollapse: ImageButton
    private lateinit var btnClearLogs: ImageButton
    private lateinit var spinnerFilter: Spinner
    
    private val logsAdapter = LogsAdapter()
    private var isExpanded = true
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_logs_panel, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        initViews(view)
        setupRecyclerView()
        setupFilterSpinner()
        setupClickListeners()
        observeViewModels()
    }
    
    private fun initViews(view: View) {
        recyclerLogs = view.findViewById(R.id.recyclerLogs)
        btnExpandCollapse = view.findViewById(R.id.btnExpandCollapse)
        btnClearLogs = view.findViewById(R.id.btnClearLogs)
        spinnerFilter = view.findViewById(R.id.spinnerFilter)
    }
    
    private fun setupRecyclerView() {
        recyclerLogs.apply {
            layoutManager = LinearLayoutManager(context).apply {
                stackFromEnd = true
            }
            adapter = logsAdapter
        }
    }
    
    private fun setupFilterSpinner() {
        val displayFilters = listOf("全部", "相机", "哨兵", "代理", "隧道", "系统")
        val filterTags = listOf(null, "Camera", "Sentry", "Proxy", "Tunnel", "System")
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, displayFilters)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerFilter.adapter = adapter
        
        spinnerFilter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val filter = if (position in filterTags.indices) filterTags[position] else null
                logsViewModel.setFilter(filter)
            }
            
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }
    
    private fun setupClickListeners() {
        btnExpandCollapse.setOnClickListener {
            mainViewModel.toggleLogsExpanded()
        }
        
        btnClearLogs.setOnClickListener {
            logsViewModel.clearLogs()
        }
    }
    
    private fun observeViewModels() {
        // Observe logs
        logsViewModel.filteredLogs.observe(viewLifecycleOwner) { logs ->
            logsAdapter.submitList(logs) {
                // Auto-scroll to bottom when new logs arrive
                if (logs.isNotEmpty() && isExpanded) {
                    recyclerLogs.scrollToPosition(logs.size - 1)
                }
            }
        }
        
        // Observe expanded state
        mainViewModel.logsExpanded.observe(viewLifecycleOwner) { expanded ->
            isExpanded = expanded
            updateExpandedState(expanded)
        }
    }
    
    private fun updateExpandedState(expanded: Boolean) {
        recyclerLogs.visibility = if (expanded) View.VISIBLE else View.GONE
        btnExpandCollapse.setImageResource(
            if (expanded) R.drawable.ic_collapse else R.drawable.ic_expand
        )
    }
}
