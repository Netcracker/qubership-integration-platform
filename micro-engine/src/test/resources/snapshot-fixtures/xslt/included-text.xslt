<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="1.0"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
    <xsl:include href="shared/customer-label.xslt"/>
    <xsl:output method="text" encoding="UTF-8"/>

    <xsl:template match="/customerData">
        <xsl:for-each select="customer">
            <xsl:if test="position() &gt; 1">
                <xsl:text>;</xsl:text>
            </xsl:if>
            <xsl:call-template name="customer-label"/>
        </xsl:for-each>
    </xsl:template>
</xsl:stylesheet>
