<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="1.0"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform">

    <xsl:output method="xml"
                encoding="UTF-8"
                indent="yes"/>

    <xsl:strip-space elements="*"/>

    <xsl:template match="/customerData">
        <clients>
            <xsl:apply-templates select="customer"/>
        </clients>
    </xsl:template>

    <xsl:template match="customer">
        <client>
            <xsl:attribute name="client-id">
                <xsl:value-of select="@id"/>
            </xsl:attribute>

            <full-name>
                <given-name>
                    <xsl:value-of select="firstName"/>
                </given-name>
                <family-name>
                    <xsl:value-of select="lastName"/>
                </family-name>
            </full-name>

            <contacts>
                <email-address>
                    <xsl:value-of select="email"/>
                </email-address>
            </contacts>

            <purchases>
                <xsl:apply-templates select="orders/order"/>
            </purchases>
        </client>
    </xsl:template>

    <xsl:template match="order">
        <purchase>
            <xsl:attribute name="purchase-id">
                <xsl:value-of select="@number"/>
            </xsl:attribute>

            <created-at>
                <xsl:value-of select="date"/>
            </created-at>

            <amount>
                <value>
                    <xsl:value-of select="total"/>
                </value>
                <currency>
                    <xsl:value-of select="total/@currency"/>
                </currency>
            </amount>

            <products>
                <xsl:apply-templates select="items/item"/>
            </products>
        </purchase>
    </xsl:template>

    <xsl:template match="item">
        <product>
            <xsl:attribute name="product-code">
                <xsl:value-of select="@sku"/>
            </xsl:attribute>

            <title>
                <xsl:value-of select="name"/>
            </title>
            <quantity>
                <xsl:value-of select="quantity"/>
            </quantity>
            <unit-price>
                <xsl:value-of select="price"/>
            </unit-price>
        </product>
    </xsl:template>

</xsl:stylesheet>
