package com.bsd.Encryption

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bsd.Encryption.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

//    lateinit var username: EditText
//    lateinit var password: EditText
//    lateinit var loginButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

//        var loginButton : Button = findViewById(R.id.loginButton)
//        loginButton.setOnClickListener {//
//        }

        binding.loginButton.setOnClickListener(View.OnClickListener {
            if (binding.username.text.toString() == "user" && binding.password.text.toString() == "1234") {
//                Toast.makeText(this, "Login Successful!", Toast.LENGTH_SHORT).show()
//                changePage()
                val intent = Intent(this@MainActivity, MainActivity4::class.java)
                startActivity(intent)

            } else {
                Toast.makeText(this, "Login Failed!", Toast.LENGTH_SHORT).show()
            }
        })
    }
}