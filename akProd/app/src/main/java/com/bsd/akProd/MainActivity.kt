package com.bsd.akProd

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.bsd.akProd.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val user = intent.getStringExtra("empNo").orEmpty()
        val name = intent.getStringExtra("empName").orEmpty()
        if (user.isBlank()) {
            finish(); return
        }

        val binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.findViewById<android.widget.TextView>(R.id.TextView11).text =
            "작업자: $user $name"

        fun open(target: Class<*>, history: Boolean = false, screenType: String = "") {
            startActivity(Intent(this, target).apply {
                putExtra("empNo", user)
                putExtra("empName", name)
                putExtra("fromMenu", history)
                putExtra("screenType", screenType)
            })
        }
        binding.btnCp3.setOnClickListener { open(Cp3Activity::class.java) }
        binding.btnMeteringC.setOnClickListener { open(ColoredMeteringActivity::class.java) }
        binding.btnCp14.setOnClickListener { open(Cp14Activity::class.java) }
        binding.btnCp3History.setOnClickListener { open(Cp3HistoryActivity::class.java, true) }
        binding.btnMeteringHistoryC.setOnClickListener {
            open(Cp3HistoryActivity::class.java, true, Cp3TableReader.METERING)
        }
        binding.btnCp14History.setOnClickListener { open(Cp14HistoryActivity::class.java, true) }
        binding.btnMetering.setOnClickListener {
            open(
                Cp14Activity::class.java,
                screenType = Cp14TableReader.METERING
            )
        }
        binding.btnMeteringHistory.setOnClickListener {
            open(
                Cp14HistoryActivity::class.java,
                true,
                Cp14TableReader.METERING
            )
        }
        binding.btnPbInjection.setOnClickListener {
            open(Cp14Activity::class.java, screenType = Cp14TableReader.PB_INJECTION)
        }
        binding.btnPbMetering.setOnClickListener {
            open(Cp14Activity::class.java, screenType = Cp14TableReader.PB_METERING)
        }
        binding.btnPbInjectionHistory.setOnClickListener {
            open(Cp14HistoryActivity::class.java, true, Cp14TableReader.PB_INJECTION)
        }
        binding.btnPbMeteringHistory.setOnClickListener {
            open(Cp14HistoryActivity::class.java, true, Cp14TableReader.PB_METERING)
        }
        binding.btnLogout.setOnClickListener { finish() }
        binding.button4.setOnClickListener { finishAffinity() }
    }
}
