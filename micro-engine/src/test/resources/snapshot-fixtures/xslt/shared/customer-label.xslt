<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="1.0"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
    <xsl:template name="customer-label">
        <xsl:value-of select="@id"/>
        <xsl:text>: </xsl:text>
        <xsl:value-of select="normalize-space(concat(firstName, ' ', lastName))"/>
    </xsl:template>
</xsl:stylesheet>
