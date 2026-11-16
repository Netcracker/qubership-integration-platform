<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="1.0"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:c="urn:qip:customer"
                xmlns:r="urn:qip:report"
                exclude-result-prefixes="c">
    <xsl:output method="xml" encoding="UTF-8" omit-xml-declaration="yes"/>
    <xsl:param name="region" select="'default-region'"/>
    <xsl:param name="label" select="'default-label'"/>

    <xsl:template match="/c:customerData">
        <r:summary region="{$region}" label="{$label}">
            <r:customer id="{c:customer/@id}">
                <xsl:value-of select="c:customer/c:name"/>
            </r:customer>
        </r:summary>
    </xsl:template>
</xsl:stylesheet>
