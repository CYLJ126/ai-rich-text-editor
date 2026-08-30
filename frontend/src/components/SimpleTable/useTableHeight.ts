import {RefObject, useEffect, useState} from 'react';

export const useTableHeight = (
    searchFormRef: RefObject<HTMLDivElement>,
    fixedOffset: number = 80,
    minHeight: number = 300,
) => {
    const [tableHeight, setTableHeight] = useState(760);

    const calculateHeight = () => {
        const windowHeight = window.innerHeight;
        const searchFormHeight = searchFormRef.current?.offsetHeight || 0;
        const newHeight = windowHeight - searchFormHeight - fixedOffset;
        setTableHeight(Math.max(newHeight, minHeight));
    };

    useEffect(() => {
        calculateHeight();

        const handleResize = () => {
            calculateHeight();
        };

        window.addEventListener('resize', handleResize);

        const resizeObserver = new ResizeObserver(() => {
            calculateHeight();
        });

        if (searchFormRef.current) {
            resizeObserver.observe(searchFormRef.current);
        }

        return () => {
            window.removeEventListener('resize', handleResize);
            resizeObserver.disconnect();
        };
    }, [searchFormRef, fixedOffset, minHeight]);

    return tableHeight;
};
