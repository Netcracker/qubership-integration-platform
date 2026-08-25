// Package httpfield answers what an HTTP header field may carry. The response a
// mock writes and the header a matcher addresses are held to the same rules, so
// the two checks live here rather than once per caller.
package httpfield

import "strings"

// nameSpecials are the characters outside the alphanumerics that the RFC 9110
// token rule allows in a field name.
const nameSpecials = "!#$%&'*+-.^_`|~"

// IsName reports whether name is an RFC 9110 token, which is what a header field
// name has to be. An empty name writes a header line that starts with a colon,
// and any character outside the token set either ends the name early or makes it
// unreadable.
func IsName(name string) bool {
	if name == "" {
		return false
	}
	for i := 0; i < len(name); i++ {
		c := name[i]
		isAlphanumeric := ('a' <= c && c <= 'z') || ('A' <= c && c <= 'Z') || ('0' <= c && c <= '9')
		if !isAlphanumeric && !strings.ContainsRune(nameSpecials, rune(c)) {
			return false
		}
	}
	return true
}

// IsValue reports whether value can be written as a header value. RFC 9110
// admits visible characters, space and horizontal tab; the remaining control
// characters truncate the header line or split the response.
func IsValue(value string) bool {
	for i := 0; i < len(value); i++ {
		c := value[i]
		if c == '\t' {
			continue
		}
		if c < ' ' || c == 0x7f {
			return false
		}
	}
	return true
}
