import React from "react";
import {Resizable, ResizableProps} from "react-resizable";

export type ResizableTitleProps = React.HTMLAttributes<HTMLElement> & {
    onResize: ResizableProps["onResize"];
    width: number | undefined;
};

export const ResizableTitle = (props: ResizableTitleProps) => {
    const { onResize, width, ...restProps } = props;

    if (!width) {
        return <th {...restProps} />;
    }

    return (
        <Resizable
            width={width}
            height={0}
            handle={
                <span
                    className="react-resizable-handle"
                    onClick={(e) => e.stopPropagation()}
                />
            }
            onResize={onResize}
            draggableOpts={{ enableUserSelectHack: false }}
        >
            <th {...restProps} style={{ ...restProps.style, width }} />
        </Resizable>
    );
};
