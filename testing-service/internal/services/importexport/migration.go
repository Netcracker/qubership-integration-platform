// Package importexport reads the entities carried by an exported archive.
package importexport

import "fmt"

// ActualDataVersion is the version exported entities are written at, and the
// only one an import accepts. The archive carries it because the format is
// published: a build that ever changes the exported shape raises this and reads
// the older versions by upgrading them here.
const ActualDataVersion = 1

// CheckDataVersion reports whether this build understands data written at the
// given version.
func CheckDataVersion(version int) error {
	if version < 1 {
		return fmt.Errorf("data version to import (%v) is below the first version (1)", version)
	}
	if version > ActualDataVersion {
		return fmt.Errorf("data version to import (%v) is higher than actual version (%v)",
			version, ActualDataVersion)
	}
	return nil
}
