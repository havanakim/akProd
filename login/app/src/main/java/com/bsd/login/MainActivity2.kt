package com.bsd.login

import android.content.Intent
import android.os.Bundle
import android.os.StrictMode
import android.os.StrictMode.ThreadPolicy
import android.util.Base64
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.bsd.login.databinding.ActivityMain2Binding
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class MainActivity2 : AppCompatActivity() {
    companion object {
        val skey   = "taekwangdatasystemtkgaikang241025"
        val ivpara = "ak-erp@2016tkgak"
    }
    private lateinit var binding: ActivityMain2Binding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main2)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main2)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        val receivedMessage: String? = intent.getStringExtra("msg")

//        dbConn()
        encoding()

        val button31: Button = binding.button21
        button31.setOnClickListener {
            val intent = Intent(this@MainActivity2, MainActivity::class.java)
            startActivity(intent)
        }

//      val button32: Button = findViewById(R.id.button32)
        val button32: Button = binding.button22
        button32.setOnClickListener {
            val intent = Intent(this@MainActivity2, MainActivity2::class.java)
            startActivity(intent)
        }
    }
    fun encoding(){
        val ip: String = "10.51.0.235"
        val port: String = "1433"
        val Classes: String = "net.sourceforge.jtds.jdbc.Driver"
        val database: String = "WMS_IF_TEST"
        val username: String = "sa"
        val password: String = "ak-erp@2016"

        val textview21: TextView = binding.textview21
        val edittext1: EditText = binding.edittext1
        val edittext2: EditText = binding.edittext2
        val edittext3: EditText = binding.edittext3
        val edittext4: EditText = binding.edittext4
        val edittext5: EditText = binding.edittext5
        val edittext6: EditText = binding.edittext6

        var text1: String = " Classes:" + Classes  + "/"+ encryptCBC(Classes)
        var text2: String = "database:" + database + "/"+ encryptCBC(database)
        var text3: String = "username:" + username + "/"+ encryptCBC(username)
        var text4: String = "password:" + password + "/"+ encryptCBC(password)
        var text5: String = "      ip:" + ip + "/"+ encryptCBC(ip)
        var text6: String = "    port:" + port + "/"+ encryptCBC(port)

        textview21.text = encryptCBC( skey + "/n/t" + ivpara)

        edittext1.setText( text1 )
        edittext2.setText( text2 )
        edittext3.setText( text3 )
        edittext4.setText( text4 )
        edittext5.setText( text5 )
        edittext6.setText( text6 )
    }

    private fun dbConn() {
        val ip: String = "10.51.0.235"
        val port: String = "1433"
        val Classes: String = "net.sourceforge.jtds.jdbc.Driver"
        val database: String = "WMS_IF_TEST"
        val username: String = "sa"
        val password: String = "ak-erp@2016"

        var rs: ResultSet
        var stmt: Statement
        var sql: String
        var str: String? = ""
        var connection: Connection? = null
        var url: String = "jdbc:jtds:sqlserver://$ip:$port/$database"

        sql =
            "SELECT ROW_NUMBER() OVER(ORDER BY INS_DATETIME DESC) AS rownum, BARCODE, A.RETRUN_YN,format(ORDER_QTY,'##0.##') as qty  " +
                    "  ,A.CANCEL_FLAG,A.IF_FLAG,CONVERT(CHAR(19), A.INS_DATETIME, 20) AS D_REG  " +
                    " FROM TB_INB_BARCODE_RSLT A " +
                    " WHERE CONVERT(CHAR(10), A.SCAN_DATETIME, 112)  = CONVERT(CHAR(10), getdate(), 112) "

        ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.INTERNET), 1)
        val policy = ThreadPolicy.Builder().permitAll().build()
        StrictMode.setThreadPolicy(policy)

        try {
            Class.forName(Classes);
            connection = DriverManager.getConnection(url, username, password);
//            Toast.makeText(this, "Connected", Toast.LENGTH_SHORT).show();
            stmt = connection.createStatement()
            rs = stmt.executeQuery(sql)
            while (rs.next()) {
                str = str +
                        rs.getString(1) + "> " + rs.getString(2) + ", " +
                        rs.getString(3) + ", " + rs.getString(4) + ",\n\t" +
                        rs.getString(5) + ", " + rs.getString(6) + ", " +
                        rs.getString(7) + "\n"

            }
            connection.close();
//            Toast.makeText(this, "Complete scanned data.", Toast.LENGTH_SHORT).show();

        } catch (e: SQLException) {
            println("Error:" + e.errorCode + ", " + e.message)
        }
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

    @Throws(Exception::class)
    fun decryptCBC(encryptedText: String): String {
        val keySpec = getKey()
        val ivSpec = getIV()
        val textToDecrypt = Base64.decode(encryptedText, Base64.DEFAULT)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5PADDING")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
        val decrypt = cipher.doFinal(textToDecrypt)
        return String(decrypt, Charsets.UTF_8)
    }

    private fun getKey(): SecretKeySpec {
        val secretKey = SecretKeySpec( skey.toByteArray(Charsets.UTF_8), "AES")
        return secretKey
    }

    private fun getIV(): IvParameterSpec {
        val iv = ivpara
        return IvParameterSpec(iv.toByteArray(Charsets.UTF_8))
    }

}