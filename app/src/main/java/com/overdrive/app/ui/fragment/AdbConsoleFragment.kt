package com.overdrive.app.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.overdrive.app.launcher.AdbDaemonLauncher
import com.overdrive.app.ui.adapter.PresetCommandAdapter
import com.overdrive.app.ui.model.PRESET_COMMANDS
import com.overdrive.app.ui.model.PresetCommand
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.overdrive.app.R

/**
 * Fragment for ADB shell console with preset commands.
 */
class AdbConsoleFragment : Fragment() {
    
    private lateinit var etCommand: TextInputEditText
    private lateinit var btnExecute: MaterialButton
    private lateinit var recyclerPresets: RecyclerView
    private lateinit var tvOutput: TextView
    private lateinit var scrollOutput: ScrollView
    private lateinit var btnClearOutput: MaterialButton
    
    private var adbLauncher: AdbDaemonLauncher? = null
    private val outputBuilder = StringBuilder()
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_adb_console, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adbLauncher = AdbDaemonLauncher(requireContext())

        initViews(view)
        setupPresetCommands()
        setupClickListeners()
    }
    
    private fun initViews(view: View) {
        etCommand = view.findViewById(R.id.etCommand)
        btnExecute = view.findViewById(R.id.btnExecute)
        recyclerPresets = view.findViewById(R.id.recyclerPresets)
        tvOutput = view.findViewById(R.id.tvOutput)
        scrollOutput = view.findViewById(R.id.scrollOutput)
        btnClearOutput = view.findViewById(R.id.btnClearOutput)
    }
    
    private fun setupPresetCommands() {
        val adapter = PresetCommandAdapter(PRESET_COMMANDS) { preset ->
            onPresetSelected(preset)
        }
        
        recyclerPresets.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            this.adapter = adapter
        }
    }
    
    private fun setupClickListeners() {
        btnExecute.setOnClickListener {
            val command = etCommand.text?.toString()?.trim()
            if (!command.isNullOrEmpty()) {
                executeCommand(command)
            }
        }
        
        etCommand.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                val command = etCommand.text?.toString()?.trim()
                if (!command.isNullOrEmpty()) {
                    executeCommand(command)
                }
                true
            } else {
                false
            }
        }
        
        btnClearOutput.setOnClickListener {
            clearOutput()
        }
    }
    
    private fun onPresetSelected(preset: PresetCommand) {
        // Just populate the command box - let user edit and execute manually
        etCommand.setText(preset.command)
        etCommand.setSelection(preset.command.length) // Move cursor to end
    }
    
    private fun executeCommand(command: String) {
        appendOutput("\n$ $command")
        btnExecute.isEnabled = false
        
        adbLauncher?.executeShellCommand(command, object : AdbDaemonLauncher.LaunchCallback {
            override fun onLog(message: String) {
                activity?.runOnUiThread {
                    appendOutput(message)
                }
            }
            
            override fun onLaunched() {
                activity?.runOnUiThread {
                    btnExecute.isEnabled = true
                    etCommand.text?.clear()
                }
            }
            
            override fun onError(error: String) {
                activity?.runOnUiThread {
                    appendOutput("错误: $error")
                    btnExecute.isEnabled = true
                }
            }
        })
    }
    
    private fun appendOutput(text: String) {
        outputBuilder.append("\n").append(text)
        tvOutput.text = outputBuilder.toString()
        scrollOutput.post {
            scrollOutput.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }
    
    private fun clearOutput() {
        outputBuilder.clear()
        outputBuilder.append(getString(R.string.adb_output_ready))
        tvOutput.text = outputBuilder.toString()
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        // Use releasePerInstanceResources — NOT closePersistentConnection.
        // closePersistentConnection nulls the process-wide shared Dadb in
        // AdbShellExecutor's companion, which would surface as spurious
        // onError on every other AdbDaemonLauncher's in-flight tasks
        // (DaemonStartupManager.adbLauncher's 30s health check, the
        // DaemonsViewModel.adbLauncher controllers, etc.). We only need to
        // release THIS fragment's executor + tunnel-poll scheduler.
        adbLauncher?.releasePerInstanceResources()
        adbLauncher = null
    }
}
