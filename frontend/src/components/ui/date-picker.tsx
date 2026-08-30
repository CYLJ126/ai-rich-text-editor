import { cn } from "@/lib/utils";
import { CalendarIcon } from "lucide-react";
import { useState } from "react";
import { buttonVariants } from "./button";
import { Popover, PopoverContent, PopoverTrigger } from "./popover";

type CalendarChildFunc = (close: () => void) => React.ReactNode;

interface DatePickerProps {
  placeholder?: string;
  value?: string;
  className?: string;
  disabled?: boolean;
  ariaLabelledby?: string;
  invalid?: boolean;
  children?: React.ReactNode | CalendarChildFunc;
}

const DatePicker = ({
  placeholder,
  value,
  className,
  disabled,
  ariaLabelledby,
  invalid,
  children,
}: DatePickerProps) => {
  const [open, setOpen] = useState(false);

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger
        className={cn(
          buttonVariants({ variant: "outline" }),
          "shadow-none w-full",
          {
            "border-destructive dark:border-destructive/50": invalid,
          },
          className,
        )}
        disabled={disabled}
        aria-labelledby={ariaLabelledby}
      >
        <span
          className={cn("text-sm text-nowrap grow text-start", {
            "text-muted-foreground": !value,
          })}
        >
          {value ? value : (placeholder ?? "Pick a date...")}
        </span>
        <CalendarIcon className="size-4 opacity-50 ms-4" />
      </PopoverTrigger>
      <PopoverContent className="w-auto p-0" align="start">
        {typeof children === "function"
          ? children?.(() => setOpen(false))
          : children}
      </PopoverContent>
    </Popover>
  );
};

export { DatePicker };
