/*
Copyright 2011-2013 Frederic Langlet
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
you may obtain a copy of the License at

                http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package function

import (
	"errors"
)

// Zero Length Encoding is a simple encoding algorithm by Wheeler
// closely related to Run Length Encoding. The main difference is
// that only runs of 0 values are processed. Also, the length is
// encoded in a different way (each digit in a different byte)
// This algorithm is well adapted to process post BWT/MTFT data

const (
	ZLT_MAX_RUN = int(1<<31) - 1
)

type ZLT struct {
	size uint
}

func NewZLT(sz uint) (*ZLT, error) {
	this := new(ZLT)
	this.size = sz
	return this, nil
}

func (this *ZLT) Size() uint {
	return this.size
}

func (this *ZLT) Forward(src, dst []byte) (uint, uint, error) {
	srcEnd := this.size

	if this.size == 0 {
		srcEnd = uint(len(src))
	}

	dstEnd := uint(len(dst))
	dstEnd2 := dstEnd - 2
	runLength := 1
	srcIdx := uint(0)
	dstIdx := uint(0)

	for srcIdx < srcEnd && dstIdx < dstEnd {
		val := src[srcIdx]

		if val == 0 {
			runLength++
			srcIdx++

			if srcIdx < srcEnd && runLength < ZLT_MAX_RUN {
				continue
			}
		}

		if runLength > 1 {
			// Encode length
			log2 := uint(1)

			for runLength>>log2 > 1 {
				log2++
			}

			if dstIdx >= dstEnd-log2 {
				break
			}

			// Write every bit as a byte except the most significant one
			for log2 > 0 {
				log2--
				dst[dstIdx] = byte((runLength >> log2) & 1)
				dstIdx++
			}

			runLength = 1
			continue
		}

		if val >= 0xFE {
			if dstIdx >= dstEnd2 {
				break
			}

			dst[dstIdx] = 0xFF
			dstIdx++
			dst[dstIdx] = val - 0xFE
			dstIdx++
		} else {
			dst[dstIdx] = val + 1
			dstIdx++
		}

		srcIdx++
	}

	if srcIdx < srcEnd {
		return srcIdx, dstIdx, errors.New("Output buffer is too small")
	}

	return srcIdx, dstIdx, nil
}

func (this *ZLT) Inverse(src, dst []byte) (uint, uint, error) {
	srcEnd := this.size

	if this.size == 0 {
		srcEnd = uint(len(src))
	}

	dstEnd := uint(len(dst))
	runLength := 1
	srcIdx := uint(0)
	dstIdx := uint(0)

	for srcIdx < srcEnd && dstIdx < dstEnd {
		if runLength > 1 {
			runLength--
			dst[dstIdx] = 0
			dstIdx++
			continue
		}

		val := src[srcIdx]

		if val <= 1 {
			// Generate the run length bit by bit (but force MSB)
			runLength = 1

			for {
				runLength = (runLength << 1) | int(val)
				srcIdx++

				if srcIdx >= srcEnd {
					break
				}

				val = src[srcIdx]
				
				if val > 1 {
					break
				}	
			}

			continue
		}

		// Regular data processing
		if val == 0xFF {
			srcIdx++
			
			if srcIdx >= srcEnd {
				break
			}	
						
			dst[dstIdx] = 0xFE + src[srcIdx]
		} else {
			dst[dstIdx] = val - 1
		}
		
		dstIdx++
		srcIdx++
	}

	// If runLength is not 1, add trailing 0s
	end := dstIdx + uint(runLength) - 1

	if end > dstEnd {
		return srcIdx, dstIdx, errors.New("Output buffer is too small")
	}

	for dstIdx < end {
		dst[dstIdx] = 0
		dstIdx++
	}

	if srcIdx < srcEnd {
		return srcIdx, dstIdx, errors.New("Output buffer is too small")
	}

	return srcIdx, dstIdx, nil
}
