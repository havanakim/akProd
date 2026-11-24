package com.bsd.akProd

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.bsd.akProd.databinding.ActivityMain4Binding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Existing ERP authentication: employee number and last four telephone digits. */
class MainActivity4 : AppCompatActivity() {
    private lateinit var binding: ActivityMain4Binding
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMain4Binding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.main4) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        binding.loginButton.setOnClickListener {
            val user = binding.empno.text.toString().trim()
            val password = binding.lPassword.text.toString().trim()
            if (user.isBlank() || password.isBlank()) {
                binding.textview41.text = "사번과 암호를 입력하세요."
                return@setOnClickListener
            }
            binding.loginButton.isEnabled = false
            binding.textview41.text = "로그인 확인 중…"
            lifecycleScope.launch {
                try {
                    val name = withContext(Dispatchers.IO) {
                        DBHelper.openConnection(BuildConfig.DB_AUTH_NAME).use { connection ->
                            connection.prepareStatement("SELECT NAME, RIGHT(hand_tel_no,4) AS PW FROM JSAK_ERP.DBO.HAA010T WHERE EMP_NO=?")
                                .use { statement ->
                                    statement.queryTimeout = 15
                                    statement.setString(1, user)
                                    statement.executeQuery().use { rows ->
                                        if (rows.next() && rows.getString("PW")
                                                ?.trim() == password
                                        ) rows.getString("NAME") else null
                                    }
                                }
                        }
                    }
                    if (name == null) binding.textview41.text = "로그인 실패! 사번/암호를 확인하세요."
                    else {
                        binding.textview41.text = ""
                        binding.lPassword.text?.clear()
                        startActivity(Intent(this@MainActivity4, MainActivity::class.java).apply {
                            putExtra("empNo", user); putExtra("empName", name)
                        })
                    }
                } catch (e: Exception) {
                    binding.textview41.text = "DB 연결/로그인 조회 실패: ${e.message}"
                } finally {
                    binding.loginButton.isEnabled = true
                }
            }
        }
    }
}