package importexport

import (
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestCheckDataVersionAcceptsTheVersionThisBuildWrites(t *testing.T) {
	require.NoError(t, CheckDataVersion(ActualDataVersion))
}

func TestCheckDataVersionRejectsDataFromANewerBuild(t *testing.T) {
	err := CheckDataVersion(ActualDataVersion + 1)

	require.Error(t, err)
	assert.ErrorContains(t, err, "higher than actual version")
}

func TestCheckDataVersionRejectsAVersionBelowOne(t *testing.T) {
	for _, version := range []int{0, -1} {
		err := CheckDataVersion(version)

		require.Error(t, err)
		assert.ErrorContains(t, err, "below the first version")
	}
}
