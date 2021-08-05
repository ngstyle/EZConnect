package com.example.simpleprov

import android.app.Application
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.security.InvalidAlgorithmParameterException
import java.security.InvalidKeyException
import java.security.NoSuchAlgorithmException
import java.security.spec.InvalidKeySpecException
import java.security.spec.KeySpec
import java.util.*
import java.util.zip.CRC32
import javax.crypto.*
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Created by chon on 2021/8/3.
 * Hedge.
 */
class EmitterTask(application: Application) {

    private val wifiManager = application.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var ssid: String? = null
    private var passphrase: ByteArray? = null
    private lateinit var customData: ByteArray
    private var cipherData: ByteArray? = null

    private lateinit var mac: List<Char>
    private lateinit var preamble: List<Char>

    private var ssidLen = 0
    private var passLen = 0
    private var customDataLen = 0
    private var cipherDataLen = 0

    private var passCRC = 0
    private var ssidCRC = 0
    private var customDataCRC = 0

    private var state = 0
    private var subState = 0

    fun resetStateMachine() {
        state = 0
        subState = 0
    }

    private fun emitRaw(u: Int, m: Int, l: Int) {
        /* multicast uppermost byte has only 7 chr */
        val u = u and 0x7f
        try {
//            Timber.d("239.$u.$m.$l")
            val data = "a".toByteArray()
            val inetAddress = InetAddress.getByName("239.$u.$m.$l")
            val packet = DatagramPacket(data, data.size, inetAddress, 5500)
            MulticastSocket(1234).run {
                send(packet)
                close()
            }
        } catch (e: Exception) {
            Timber.e(e)
        }
    }

    private fun emitState0(subState: Int) {
        // Frame-type for preamble is 0b11110<subState1><subState0>
        // i = <frame-type> | <subState> i.e. 0x78 | subState
        val k: Int = preamble[2 * subState].code
        val j: Int = preamble[2 * subState + 1].code
        val i: Int = subState or 0x78
        emitRaw(i, j, k)
    }

    private fun emitState1(subState: Int, len: Int) {
        // Frame-type for SSID is 0b10<5 subState bits>
        // u = <frame-type> | <subState> i.e. 0x40 | subState
        if (subState == 0) {
            val u = 0x40
            emitRaw(u, ssidLen, ssidLen)
        } else if (subState == 1 || subState == 2) {
            val k = (ssidCRC shr (2 * (subState - 1) + 0) * 8) and 0xff
            val j = (ssidCRC shr (2 * (subState - 1) + 1) * 8) and 0xff
            val i = subState or 0x40
            emitRaw(i, j, k)
        } else {
            val u = 0x40 or subState
            val l = 0xff and ssid!!.toByteArray()[2 * (subState - 3)].toInt()
            val m: Int =
                if (len == 2) 0xff and ssid!!.toByteArray()[2 * (subState - 3) + 1].toInt() else 0
            emitRaw(u, m, l)
        }
    }

    private fun emitState2(subState: Int, len: Int) {
        // Frame-type for Passphrase is 0b0<6 subState bits>
        // u = <frame-type> | <subState> i.e. 0x00 | subState
        if (subState == 0) {
            val u = 0x00
            emitRaw(u, passLen, passLen)
        } else if (subState == 1 || subState == 2) {
            val k = (passCRC shr (2 * (subState - 1) + 0) * 8) and 0xff
            val j = (passCRC shr (2 * (subState - 1) + 1) * 8) and 0xff
            val i = subState
            emitRaw(i, j, k)
        } else {
            if (passphrase != null) {
                val u = subState
                val l = 0xff and passphrase!![2 * (subState - 3)].toInt()
                val m = if (len == 2) 0xff and passphrase!![2 * (subState - 3) + 1].toInt() else 0
                emitRaw(u, m, l)
            }
        }
    }

    private fun emitState3(subState: Int, len: Int) {
        if (subState == 0) {
            val u = 0x60
            emitRaw(u, customDataLen, customDataLen)
        } else if (subState == 1 || subState == 2) {
            val k = (customDataCRC shr (2 * (subState - 1) + 0) * 8) and 0xff
            val j = (customDataCRC shr (2 * (subState - 1) + 1) * 8) and 0xff
            val i = subState or 0x60
            emitRaw(i, j, k)
        } else {
            if (cipherData != null) {
                val u = 0x60 or subState
                val l = 0xff and cipherData!![2 * (subState - 3)].toInt()
                val m: Int =
                    if (len == 2) 0xff and cipherData!![2 * (subState - 3) + 1].toInt() else 0
                emitRaw(u, m, l)
            }
        }
    }

    private fun stateMachine() {
        when (state) {
            0 -> if (subState == 3) {
                state = 1
                subState = 0
            } else {
                emitState0(subState)
                subState++
            }
            1 -> {
                emitState1(subState, 2)
                subState++
                if (ssidLen % 2 == 1) {
                    if (subState * 2 == ssidLen + 5) {
                        emitState1(subState, 1)
                        state = 2
                        subState = 0
                    }
                } else {
                    if ((subState - 1) * 2 == ssidLen + 4) {
                        state = 2
                        subState = 0
                    }
                }
            }
            2 -> {
                emitState2(subState, 2)
                subState++
                if (passLen % 2 == 1) {
                    if (subState * 2 == passLen + 5) {
                        emitState2(subState, 1)
                        state = 3
                        subState = 0
                    }
                } else {
                    if ((subState - 1) * 2 == passLen + 4) {
                        state = 3
                        subState = 0
                    }
                }
            }
            3 -> {
                emitState3(subState, 2)
                subState++
                if (cipherDataLen % 2 == 1) {
                    if (subState * 2 == cipherDataLen + 5) {
                        emitState3(subState, 1)
                        state = 0
                        subState = 0
                    }
                } else {
                    if ((subState - 1) * 2 == cipherDataLen + 4) {
                        state = 0
                        subState = 0
                    }
                }
            }
            else -> Timber.e("I shouldn't be here")
        }
    }

    suspend fun execute(ssidParam: String, pass: String, key: String, data: String) {
        initParam(ssidParam, pass, key, data)
        withContext(Dispatchers.IO) {
            val multicastLock = wifiManager.createMulticastLock("multicastLock")
            multicastLock.acquire()
            var i = 0
            while (i <= 600) {
                if (state == 0 && subState == 0) i++
                stateMachine()

                if (i % 100 == 0) {
                    Timber.e("Times: $i")
                }

                if (!isActive) {
                    break
                }
            }
            multicastLock.release()
        }
    }

    private fun initParam(ssidParam: String, pass: String, key: String, data: String) {
        // 1
        val crc32 = CRC32().apply {
            reset()
            update(pass.toByteArray())
        }
        passCRC = crc32.value.toInt() and -0x1
        ssid = ssidParam
        ssidLen = ssidParam.length
        customDataLen = data.length / 2
        cipherDataLen = if (customDataLen % 16 == 0) {
            customDataLen
        } else {
            (customDataLen / 16 + 1) * 16
        }
        customData = hexStringToByteArray(data, cipherDataLen)


        // 2
        val crc32CustomData = CRC32().apply {
            reset()
            update(customData)
        }
        customDataCRC = crc32CustomData.value.toInt() and -0x1
//        if (Build.VERSION.SDK_INT >= 17) {
            if (ssid?.startsWith("\"") == true && ssid?.endsWith("\"") == true) {
                ssidLen -= 2
                ssid = ssid?.substring(1, ssid!!.length.minus(1))
            }
//        }

        // 3
        val crc32Ssid = CRC32().apply {
            reset()
            update(ssid?.toByteArray())
        }

        ssidCRC = crc32Ssid.value.toInt() and -0x1
        if (key.isNotEmpty()) {
            passLen = if (pass.length % 16 == 0) {
                pass.length
            } else {
                16 - pass.length % 16 + pass.length
            }
            val plainPass = ByteArray(passLen)
            for (i in pass.indices) plainPass[i] = pass.toByteArray()[i]
            passphrase = encryptData(key, plainPass, ssid)
            cipherData = encryptData(key, customData, ssid)
        } else {
            passphrase = pass.toByteArray()
            passLen = pass.length
        }

        preamble = listOf(
            0x45.toChar(),
            0x5a.toChar(),
            0x50.toChar(),
            0x52.toChar(),
            0x32.toChar(),
            0x32.toChar()
        )

        Timber.d(wifiManager.connectionInfo!!.bssid)
        Timber.d("ssid = $ssid, len = $ssidLen, pass = $pass, len = $passLen, key = $key")
        mac = wifiManager.connectionInfo.bssid.split(":").map {
            it.toInt(16).toChar()
        }

        resetStateMachine()
    }

    private fun hexStringToByteArray(s: String, blockLen: Int): ByteArray {
        val len = s.length
        val data = ByteArray(blockLen)
        Arrays.fill(data, 0.toByte())
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(
                s[i + 1], 16
            )).toByte()
            i += 2
        }
        return data
    }

    private fun encryptData(key: String, plainText: ByteArray?, ssid: String?): ByteArray? {
        val iv = ByteArray(16)
        for (i in 0..15) iv[i] = 0
        val ivSpec = IvParameterSpec(iv)
        val cipher: Cipher
        var encrypted: ByteArray? = null
        try {
            val iterationCount = 4096
            val keyLength = 256
            val salt = ssid?.toByteArray()
            val keySpec: KeySpec = PBEKeySpec(key.toCharArray(), salt, iterationCount, keyLength)
            val keyFactory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
            val keyBytes = keyFactory.generateSecret(keySpec).encoded
            val skeySpec = SecretKeySpec(keyBytes, "AES")
            cipher = Cipher.getInstance("AES/CBC/PKCS7Padding")
            cipher.init(Cipher.ENCRYPT_MODE, skeySpec, ivSpec)
            encrypted = cipher.doFinal(plainText)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return encrypted
    }
}