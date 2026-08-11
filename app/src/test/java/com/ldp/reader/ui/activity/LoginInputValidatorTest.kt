package com.ldp.reader.ui.activity

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginInputValidatorTest {

    @Test
    fun `mainland mobile number requires a current 11 digit prefix`() {
        listOf(
            "",
            "1380013800",
            "138001380000",
            "1380013800a",
            "23800138000",
            "12800138000"
        ).forEach { phone ->
            assertFalse("phone should be rejected: $phone", LoginInputValidator.isMainlandMobile(phone))
        }

        listOf("13000000000", "13800138000", "19912345678").forEach { phone ->
            assertTrue("phone should be accepted: $phone", LoginInputValidator.isMainlandMobile(phone))
        }
    }

    @Test
    fun `sms code must be four to eight ASCII digits`() {
        listOf("", "123", "123456789", "12a4", "１２３４").forEach { code ->
            assertFalse("code should be rejected: $code", LoginInputValidator.isSmsCode(code))
        }

        listOf("1234", "123456", "12345678").forEach { code ->
            assertTrue("code should be accepted: $code", LoginInputValidator.isSmsCode(code))
        }
    }
}
