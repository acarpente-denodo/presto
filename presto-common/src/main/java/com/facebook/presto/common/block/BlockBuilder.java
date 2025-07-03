/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.common.block;

import io.airlift.slice.Slice;
import io.airlift.slice.SliceInput;

public interface BlockBuilder
        extends Block
{
    /**
     * Write a byte to the current entry;
     */
    default BlockBuilder writeByte(int value)
    {
        throw new UnsupportedOperationException(getClass().getName());
    }

    /**
     * Write a short to the current entry;
     */
    default BlockBuilder writeShort(int value)
    {
        throw new UnsupportedOperationException(getClass().getName());
    }

    /**
     * Write a int to the current entry;
     */
    default BlockBuilder writeInt(int value)
    {
        throw new UnsupportedOperationException(getClass().getName());
    }

    /**
     * Write a long to the current entry;
     */
    default BlockBuilder writeLong(long value)
    {
        throw new UnsupportedOperationException(getClass().getName());
    }

    /**
     * Write a byte sequences to the current entry;
     */
    default BlockBuilder writeBytes(Slice source, int sourceIndex, int length)
    {
        throw new UnsupportedOperationException(getClass().getName());
    }

    /**
     * Return a writer to the current entry. The caller can operate on the returned caller to incrementally build the object. This is generally more efficient than
     * building the object elsewhere and call writeObject afterwards because a large chunk of memory could potentially be unnecessarily copied in this process.
     */
    default BlockBuilder beginBlockEntry()
    {
        throw new UnsupportedOperationException(getClass().getName());
    }

    /**
     * Start a nested entry on the builder. beginBlockEntry returns an instance of block builder to write the nested entry. beginBlockEntry returns
     * a blockBuilder instance and delegates the call to the nested entry block builder. beginDirectEntry does not allocate the blockBuilder,
     * and it is the caller's responsibility to get the nested block builder and populate the nested block builder.
     */
    default void beginDirectEntry()
    {
        throw new UnsupportedOperationException(getClass().getName());
    }

    /**
     * Create a new block from the current materialized block by keeping the same elements
     * only with respect to {@code visiblePositions}.
     */
    default Block getPositions(int[] visiblePositions, int offset, int length)
    {
        return build().getPositions(visiblePositions, offset, length);
    }

    /**
     * Closes the current entry.
     */
    BlockBuilder closeEntry();

    /**
     * Appends a null value to the block.
     */
    BlockBuilder appendNull();

    /**
     * Append a struct to the block and close the entry.
     */
    default BlockBuilder appendStructure(Block value)
    {
        throw new UnsupportedOperationException(getClass().getName());
    }

    /**
     * Do not use this interface outside block package.
     * Instead, use Block.writePositionTo(BlockBuilder, position)
     */
    default BlockBuilder appendStructureInternal(Block block, int position)
    {
        throw new UnsupportedOperationException(getClass().getName());
    }

    /**
     * Read a single position from the input
     */
    BlockBuilder readPositionFrom(SliceInput input);

    /**
     * Builds the block. This method can be called multiple times.
     */
    Block build();

    /**
     * Creates a new block builder of the same type based on the current usage statistics of this block builder.
     */
    BlockBuilder newBlockBuilderLike(BlockBuilderStatus blockBuilderStatus);

    /**
     * Creates a new block builder of the same type based on the expectedEntries and the current usage statistics of this block builder.
     */
    BlockBuilder newBlockBuilderLike(BlockBuilderStatus blockBuilderStatus, int expectedEntries);

    // TODO #20578: Check if the following methods are really necessary. In Presto there is no ValueBlock.
    //                          It seems that these two methods and the implementations in all classes that inherit from Block can be removed.
    /**
     * Returns a block that contains a copy of the contents of the current block, and an appended null at the end. The
     * original block will not be modified. The purpose of this method is to leverage the contents of a block and the
     * structure of the implementation to efficiently produce a copy of the block with a NULL element inserted - so that
     * it can be used as a dictionary. This method is expected to be invoked on completely built {@link Block} instances
     * i.e. not on in-progress block builders.
     */
    @Override
    default Block copyWithAppendedNull()
    {
        throw new UnsupportedOperationException("BlockBuilder does not support newBlockWithAppendedNull()");
    }

    /**
     * Returns the underlying value block underlying this block.
     */
    @Override
    default Block getUnderlyingValueBlock()
    {
        return this;
    }

    /**
     * Returns the position in the underlying value block corresponding to the specified position in this block.
     */
    @Override
    default int getUnderlyingValuePosition(int position)
    {
        return position;
    }
}
