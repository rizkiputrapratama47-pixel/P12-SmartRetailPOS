
package com.industri.smartpos

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlinx.coroutines.CoroutineExceptionHandler

class PosActivity : AppCompatActivity() {

    private lateinit var etBarcode: EditText
    private lateinit var btnBayar: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var cardResult: View
    private lateinit var tvResult: TextView
    private lateinit var rgSimulation: RadioGroup
    private var retryAttempt = 0
    private val maxTransactionLimit = 10_000_000.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pos)

        etBarcode = findViewById(R.id.etBarcode)
        btnBayar = findViewById(R.id.btnBayar)
        progressBar = findViewById(R.id.progressBar)
        cardResult = findViewById(R.id.cardResult)
        tvResult = findViewById(R.id.tvResult)
        rgSimulation = findViewById(R.id.rgSimulation)

        btnBayar.setOnClickListener {
            val barcode = etBarcode.text.toString().trim()

            if (barcode.isEmpty()) {
                Toast.makeText(
                    this,
                    "Barcode wajib diisi",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                eksekusiTransaksi(barcode)
            }
        }
    }


    private fun eksekusiTransaksi(barcode: String) {
        val nominalTransaksi = 21_500.0

        try {
            if (nominalTransaksi > maxTransactionLimit) {
                throw CashierLimitExceededException(maxTransactionLimit)
            }
        } catch (error: CashierLimitExceededException) {
            Timber.w(error, "Memerlukan otorisasi supervisor")

            val inputPin = android.widget.EditText(this)
            inputPin.inputType =
                android.text.InputType.TYPE_CLASS_NUMBER or
                        android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            inputPin.hint = "Masukkan PIN Supervisor"

            val container = android.widget.LinearLayout(this)
            container.orientation = android.widget.LinearLayout.VERTICAL
            container.setPadding(48, 16, 48, 0)
            container.addView(inputPin)

            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Otorisasi Supervisor")
                .setMessage(
                    "${error.message}\n\nMasukkan PIN Supervisor untuk melanjutkan."
                )
                .setView(container)
                .setPositiveButton("Verifikasi", null)
                .setNegativeButton("Batal", null)
                .create()
                .apply {
                    setOnShowListener {
                        getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                            .setOnClickListener {
                                val pin = inputPin.text.toString()

                                if (pin == "123456") {
                                    dismiss()

                                    androidx.appcompat.app.AlertDialog.Builder(this@PosActivity)
                                        .setTitle("Otorisasi Berhasil")
                                        .setMessage(
                                            "PIN Supervisor valid. Otorisasi simulasi berhasil."
                                        )
                                        .setPositiveButton("Mengerti", null)
                                        .show()
                                } else {
                                    inputPin.error = "PIN Supervisor salah"
                                }
                            }
                    }
                    show()
                }

            return
        }

        tampilkanStatus(UiState.Loading)


        val coroutineExceptionHandler = CoroutineExceptionHandler { _, exception ->
            Timber.e(exception, "Kesalahan coroutine tidak tertangani")

            runOnUiThread {
                progressBar.visibility = View.GONE
                btnBayar.isEnabled = true

                Snackbar.make(
                    findViewById(R.id.coordinatorLayout),
                    "Kesalahan sistem: ${exception.localizedMessage ?: "Terjadi kesalahan tak terduga"}",
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }

        // Ambil pilihan simulasi dari UI sebelum menjalankan coroutine.
        val simulasi = rgSimulation.checkedRadioButtonId

        lifecycleScope.launch(coroutineExceptionHandler) {
            val hasil = withContext(Dispatchers.IO) {
                runCatching {
                    Timber.d("Memproses transaksi barcode: %s", barcode)
                    delay(1200)

                    when (simulasi) {
                        R.id.rbBarcodeError -> {
                            throw ProductBarcodeNotFoundException(barcode)
                        }

                        R.id.rbNetworkError -> {
                            throw PaymentGatewayTimeoutException(
                                "QRIS Bank Settlement"
                            )
                        }

                        else -> {
                            PosTransaction(
                                transactionId = "TRX-2026-9901",
                                barcode = barcode,
                                productName = "Susu UHT Full Cream 1 Liter",
                                totalAmount = 21500.0,
                                status = "SETTLED_SUCCESS"
                            )
                        }
                    }
                }
            }

            progressBar.visibility = View.GONE
            btnBayar.isEnabled = true

            hasil.onSuccess { trx ->
                retryAttempt = 0
                Timber.i("Transaksi berhasil: %s", trx.transactionId)
                tampilkanStatus(UiState.Success(trx))
            }
                .onFailure { error ->
                    Timber.w(error, "Transaksi gagal")

                    val pesan = error.message ?: "Terjadi kesalahan"
                    val bisaCobaLagi = error is PaymentGatewayTimeoutException

                    tampilkanStatus(
                        UiState.Error(
                            message = pesan,
                            canRetry = bisaCobaLagi
                        )
                    )

                    val snackbar = Snackbar.make(
                        findViewById(R.id.coordinatorLayout),
                        pesan,
                        Snackbar.LENGTH_LONG
                    )

                    if (bisaCobaLagi) {
                        snackbar.setAction("COBA LAGI") {
                            retryAttempt++

                            if (retryAttempt <= 3) {
                                eksekusiTransaksi(barcode)
                            } else {
                                retryAttempt = 0

                                androidx.appcompat.app.AlertDialog.Builder(this@PosActivity)
                                    .setTitle("Pembayaran Gagal")
                                    .setMessage(
                                        "Pembayaran jaringan gagal setelah 3 kali percobaan ulang. " +
                                                "Silakan alihkan pelanggan ke Pembayaran Tunai Manual."
                                    )
                                    .setPositiveButton("Mengerti", null)
                                    .show()
                            }
                        }
                    }

                    snackbar.show()
                }
        }
    }

    private fun tampilkanStatus(state: UiState<PosTransaction>) {
        when (state) {
            is UiState.Idle -> {
                progressBar.visibility = View.GONE
                cardResult.visibility = View.GONE
                btnBayar.isEnabled = true
            }

            is UiState.Loading -> {
                progressBar.visibility = View.VISIBLE
                cardResult.visibility = View.GONE
                btnBayar.isEnabled = false
            }

            is UiState.Success -> {
                progressBar.visibility = View.GONE
                cardResult.visibility = View.VISIBLE
                btnBayar.isEnabled = true

                val trx = state.data
                tvResult.text = """
                TRANSAKSI BERHASIL (LUNAS)

                ID Transaksi: ${trx.transactionId}
                Barcode: ${trx.barcode}
                Produk: ${trx.productName}
                Total Bayar: Rp ${"%,.0f".format(trx.totalAmount)}
                Status: ${trx.status}
            """.trimIndent()
            }

            is UiState.Error -> {
                progressBar.visibility = View.GONE
                cardResult.visibility = View.GONE
                btnBayar.isEnabled = true

                Snackbar.make(
                    findViewById(R.id.coordinatorLayout),
                    state.message,
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }
}
