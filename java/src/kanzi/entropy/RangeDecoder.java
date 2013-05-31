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

package kanzi.entropy;

import kanzi.InputBitStream;
import kanzi.BitStreamException;


// Based on Order 0 range coder by Dmitry Subbotin itself derived from the algorithm
// described by G.N.N Martin in his seminal article in 1979.
// [G.N.N. Martin on the Data Recording Conference, Southampton, 1979]
// Optimized for speed.

// Not thread safe
public final class RangeDecoder extends AbstractDecoder
{
    private static final long TOP       = 1L << 48;
    private static final long BOTTOM    = (1L << 40) - 1;
    private static final long MAX_RANGE = BOTTOM + 1;
    private static final long MASK      = 0x00FFFFFFFFFFFFFFL;

    private static final int NB_SYMBOLS = 257; //256 + EOF
    private static final int LAST = NB_SYMBOLS - 1;

    private long code;
    private long low;
    private long range;
    private final int[] baseFreq;
    private final int[] deltaFreq;
    private final InputBitStream bitstream;
    private boolean initialized;


    public RangeDecoder(InputBitStream bitstream)
    {
        if (bitstream == null)
            throw new NullPointerException("Invalid null bitstream parameter");

        this.range = (TOP << 8) - 1;
        this.bitstream = bitstream;
        
        // Since the frequency update after each byte decoded is the bottleneck,
        // split the frequency table into an array of absolute frequencies (with
        // indexes multiple of 16) and delta frequencies (relative to the previous
        // absolute frequency) with indexes in the [0..15] range
        this.deltaFreq = new int[NB_SYMBOLS+1];
        this.baseFreq = new int[(NB_SYMBOLS>>4)+1];

        for (int i=0; i<this.deltaFreq.length; i++)
            this.deltaFreq[i] = i & 15; // DELTA

        for (int i=0; i<this.baseFreq.length; i++)
            this.baseFreq[i] = i << 4; // BASE
    }

    
    @Override
    public int decode(byte[] array, int blkptr, int len)
    {
      if ((array == null) || (blkptr + len > array.length) || (blkptr < 0) || (len < 0))
         return -1;

      final int end = blkptr + len;
      int i = blkptr;
      this.initialize();

      try
      {
         while (i < end)
            array[i++] = this.decodeByte_();
      }
      catch (BitStreamException e)
      {
         // Fallback
      }

      return i - blkptr;
    }

    
    public boolean isInitialized()
    {
       return this.initialized;
    }
    
    
    public void initialize() 
    {
       if (this.initialized == true)
          return;
       
       // Reading the bitstream is deferred (not in constructor)
       this.initialized = true;
       this.code = this.bitstream.readBits(56) & 0xFFFFFFFF;
    }
   
    
    @Override
    public byte decodeByte()
    {
       if (this.initialized == false)
          this.initialize();        
       
       return this.decodeByte_();
    }
    
    
    // This method is on the speed critical path (called for each byte)
    // The speed optimization is focused on reducing the frequency table update
    private byte decodeByte_()
    {
       final int[] bfreq = this.baseFreq;
       final int[] dfreq = this.deltaFreq;        
       this.range /= (bfreq[NB_SYMBOLS>>4] + dfreq[NB_SYMBOLS]);
       final int count = (int) ((this.code - this.low) / this.range);

       // Find first frequency less than 'count'
       final int value = this.findSymbol(count);
        
       if (value == LAST)
       {
          if (this.bitstream.hasMoreToRead() == false)
             throw new BitStreamException("End of bitstream", BitStreamException.END_OF_STREAM);

          throw new BitStreamException("Unknown symbol: "+value, BitStreamException.INVALID_STREAM);
       }

       final int symbolLow = bfreq[value>>4] + dfreq[value];
       final int symbolHigh = bfreq[(value+1)>>4] + dfreq[value+1];

       // Decode symbol
       this.low += (symbolLow * this.range);
       this.range *= (symbolHigh - symbolLow);

       while (true)
       {                       
          if (((this.low ^ (this.low + this.range)) & MASK) >= TOP)
          {
             if (this.range >= MAX_RANGE)
                break;
             else // Normalize
                this.range = (-this.low & MASK) & BOTTOM;
          }

          this.code = (this.code << 8) | (this.bitstream.readBits(8) & 0xFF);
          this.range <<= 8;
          this.low <<= 8;
       }

       // Update frequencies: computational bottleneck !!!
       this.updateFrequencies(value+1);     
       return (byte) (value & 0xFF);
    }

    
    private int findSymbol(int freq)
    {
       final int[] bfreq = this.baseFreq;
       final int[] dfreq = this.deltaFreq;        

       // Find first frequency less than 'count'
       int value = (freq < bfreq[bfreq.length/2]) ? bfreq.length/2 - 1 : bfreq.length - 1;
        
       while ((value > 0) && (freq < bfreq[value]))
          value--;

       freq -= bfreq[value];
       value <<= 4;

       if (freq > 0)
       {
          final int end = value;
           
          if (freq < dfreq[value+8])
          {
             value = (freq < dfreq[value+4]) ? value + 3 : value + 7;
          }
          else
          {
             value = (freq < dfreq[value+12]) ? value + 11 : value + 15;
              
             if (value > NB_SYMBOLS)
                value = NB_SYMBOLS;
          }

          while ((value >= end) && (freq < dfreq[value]))
            value--;
       }
     
       return value;
    }
    
 
    private void updateFrequencies(int value)
    {
        final int start = (value + 15) >> 4;

        // Update absolute frequencies
        for (int j=this.baseFreq.length-1; j>=start; j--)
            this.baseFreq[j]++;

        // Update relative frequencies (in the 'right' segment only)
        for (int j=(start<<4)-1; j>=value; j--)
            this.deltaFreq[j]++;
    }


    @Override
    public void dispose()
    {
    }


    @Override
    public InputBitStream getBitStream()
    {
       return this.bitstream;
   }
}
