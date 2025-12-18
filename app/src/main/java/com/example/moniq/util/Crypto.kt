package com.example.moniq.util

import java.math.BigInteger
import java.security.MessageDigest

object Crypto {
    fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        val bigInt = BigInteger(1, digest)
        return String.format("%032x", bigInt)
    }
}
