<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="1.0"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
    <xsl:output method="xml" encoding="UTF-8" omit-xml-declaration="yes"/>

    <xsl:template match="/customerData">
        <xsl:if test="@reject = 'true'">
            <xsl:message terminate="yes">Customer data rejected</xsl:message>
        </xsl:if>
        <accepted id="{customer/@id}"/>
    </xsl:template>
</xsl:stylesheet>
