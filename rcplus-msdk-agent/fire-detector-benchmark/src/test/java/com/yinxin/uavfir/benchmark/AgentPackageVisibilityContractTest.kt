package com.yinxin.uavfir.benchmark

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentPackageVisibilityContractTest {
    @Test
    fun manifest_allowsTheBenchmarkToVerifyOnlyTheFormalAgentPackage() {
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(File("src/main/AndroidManifest.xml"))
        val packages = document.getElementsByTagName("package")

        assertEquals(1, packages.length)
        assertEquals("com.yinxin.uavfir", packages.item(0).attributes.getNamedItem("android:name").nodeValue)
    }
}
