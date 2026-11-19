package com.bsd.Encryption

import android.os.Bundle
import android.util.Base64
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class MainActivity4 : AppCompatActivity() {
    companion object {
        val skey   = "taekwangdatasystemtkgaikang20242"
        val ivpara = "ak-erp@2016tkgak"
    }
//    private lateinit var binding: ActivityMain4Binding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main4)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        readDBinfo()

        var Button1 : Button = findViewById(R.id.Button1)
        Button1.setOnClickListener {
//            val intent = Intent(this@MainActivity4, MainActivity4::class.java)
//            startActivity(intent)

            encoding()
        }
    }
    fun readDBinfo(){
        val ip: String      = getString(R.string.ip)
        val port: String    = getString(R.string.port)
        val Classes: String = getString(R.string.Classes)
        val database: String = getString(R.string.database)
        val username: String = getString(R.string.username)
        val password: String = getString(R.string.password)

        val textview21: TextView = findViewById(R.id.textview21)
        val edittext1: EditText = findViewById(R.id.edittext1)
        val edittext2: EditText = findViewById(R.id.edittext2)
        val edittext3: EditText = findViewById(R.id.edittext3)
        val edittext4: EditText = findViewById(R.id.edittext4)
        val edittext5: EditText = findViewById(R.id.edittext5)
        val edittext6: EditText = findViewById(R.id.edittext6)

        textview21.text =  skey +"\n\t,LEN :"+ skey.length + " digits\n" + ivpara +"\t,LEN :"  + ivpara.length + "digits"
        edittext1.setText("1. ip      :" +  ip)
        edittext2.setText("2. port    :" +  port )
        edittext3.setText("3. Classes :" +  Classes )
        edittext4.setText("4. database:" +  database )
        edittext5.setText("5. username:" +  username )
        edittext6.setText("6. password:" +  password )
    }

    fun encoding(){
//        val textview22: TextView = findViewById(R.id.textview22)
        val edittext7: EditText = findViewById(R.id.edittext7)
        val edittext8: EditText = findViewById(R.id.edittext8)
        val edittext9: EditText = findViewById(R.id.edittext9)
        val edittext10: EditText = findViewById(R.id.edittext10)
        val edittext11: EditText = findViewById(R.id.edittext11)
        val edittext12: EditText = findViewById(R.id.edittext12)

        val ip2: String = encryptCBC(getString(R.string.ip))
        val port2: String = encryptCBC(getString(R.string.port))
        val Classes2: String = encryptCBC(getString(R.string.Classes))
        val database2: String = encryptCBC(getString(R.string.database))
        val username2: String = encryptCBC(getString(R.string.username))
        val password2: String = encryptCBC(getString(R.string.password))

        edittext7.setText("1:" +  ip2)
        edittext8.setText("2:" +  port2 )
        edittext9.setText("3:" + Classes2 )
        edittext10.setText("4:" + database2 )
        edittext11.setText("5:" +  username2 )
        edittext12.setText("6:" +  password2 )
    }

    //     AES 256 CBC 복호화
    @Throws(Exception::class)
    fun encryptCBC(textToEncrypt: String): String {
        val keySpec = getKey()
        val ivSpec = getIV()
        val plainText = textToEncrypt.toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5PADDING")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
        val ciphertext = cipher.doFinal(plainText)
        return String(Base64.encode(ciphertext, Base64.DEFAULT))
    }

//    @Throws(Exception::class)
//    fun decryptCBC(encryptedText: String): String {
//        val keySpec = getKey()
//        val ivSpec = getIV()
//        val textToDecrypt = Base64.decode(encryptedText, Base64.DEFAULT)
//        val cipher = Cipher.getInstance("AES/CBC/PKCS5PADDING")
//        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
//        val decrypt = cipher.doFinal(textToDecrypt)
//        return String(decrypt, Charsets.UTF_8)
//    }

    private fun getKey(): SecretKeySpec {
        val secretKey = SecretKeySpec( skey.toByteArray(Charsets.UTF_8), "AES")
        return secretKey
    }

    private fun getIV(): IvParameterSpec {
        val iv = ivpara
        return IvParameterSpec(iv.toByteArray(Charsets.UTF_8))
    }
}