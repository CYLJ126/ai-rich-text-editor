import { cn } from "@/lib/utils";
import { ChevronDownIcon, Trash2Icon } from "lucide-react";
import { ReactNode, useState } from "react";
import { buttonVariants } from "./button";
import { Input } from "./input";
import { Item, ItemActions, ItemContent, ItemTitle } from "./item";
import { Popover, PopoverContent, PopoverTrigger } from "./popover";
import { Separator } from "./separator";

interface BaseAutocompleteDropdownProps<T> {
  items: T[];
  value?: T;
  placeholder?: string;
  renderItem: (value: T) => ReactNode;
  getKey: (value: T) => string;
  showCreateSuggestion?: boolean;
  onSelect?: (item: T) => void;
  onDelete?: (item: T) => void;
}

interface WithCreateSuggestionProps<
  T,
> extends BaseAutocompleteDropdownProps<T> {
  showCreateSuggestion: true;
  onCreate: (value: string) => void;
}

interface WithoutCreateSuggestionProps<
  T,
> extends BaseAutocompleteDropdownProps<T> {
  showCreateSuggestion?: false;
  onCreate?: (value: string) => void;
}

type AutocompleteDropdownProps<T> =
  | WithCreateSuggestionProps<T>
  | WithoutCreateSuggestionProps<T>;

function AutocompleteDropdown<T>({
  items,
  value,
  placeholder,
  renderItem,
  showCreateSuggestion,
  onCreate,
  onDelete,
  getKey,
  onSelect,
}: AutocompleteDropdownProps<T>) {
  const [search, setSearch] = useState<string>();
  const [open, setOpen] = useState(false);

  const contents = () => {
    const list = items.filter((v) => {
      if (!search) {
        return true;
      }

      return search === getKey(v);
    });

    if (showCreateSuggestion && list.length === 0 && search) {
      return (
        <Item
          className="px-2 py-1.5 hover:bg-accent select-none"
          role="button"
          onClick={() => {
            onCreate?.(search);
            setSearch(undefined);
            setOpen(false);
          }}
        >
          <ItemContent>
            <ItemTitle className="gap-0">
              <span>Create:&nbsp;</span>
              <span className="font-normal text-muted-foreground">
                {search}
              </span>
            </ItemTitle>
          </ItemContent>
        </Item>
      );
    } else if (list.length === 0) {
      return (
        <div className="text-muted-foreground hover:text-muted-foreground focus:text-muted-foreground p-2">
          No data
        </div>
      );
    }

    return list.map((item, i) => {
      return (
        <Item
          key={i}
          onClick={() => {
            onSelect?.(item);
            setOpen(false);
          }}
          className="px-2 py-1.5 hover:bg-accent select-none"
          role="button"
        >
          <ItemContent>
            <ItemTitle className="font-normal">{renderItem(item)}</ItemTitle>
          </ItemContent>
          <ItemActions>
            <div
              className="text-destructive hover:text-destructive/80 inset-e-1"
              onClick={(evt) => {
                evt.stopPropagation();
                onDelete?.(item);
              }}
            >
              <Trash2Icon className="size-4" />
            </div>
          </ItemActions>
        </Item>
      );
    });
  };
  return (
    <Popover
      open={open}
      onOpenChange={(op) => {
        setOpen(op);
        if (op) {
          setSearch(undefined);
        }
      }}
    >
      <PopoverTrigger
        className={cn(
          buttonVariants({ variant: "outline" }),
          "shadow-none w-full",
        )}
      >
        <span
          className={cn("text-sm text-nowrap grow text-start", {
            "text-muted-foreground": !value,
          })}
        >
          {value ? `${value}` : placeholder}
        </span>
        <ChevronDownIcon className="size-4 opacity-50 ms-4" />
      </PopoverTrigger>
      <PopoverContent
        align="start"
        className="w-fit p-0"
        // style={{
        //   width: "var(--anchor-width)",
        // }}
      >
        <div className="flex flex-col gap-0.5">
          <div className="p-1.5">
            <Input
              value={search ?? ""}
              onChange={(evt) => {
                console.log(evt.target.value);
                setSearch(evt.target.value);
              }}
            />
          </div>
          <Separator />
          <div className="flex flex-col p-1">{contents()}</div>
        </div>
      </PopoverContent>
    </Popover>
  );
}

export { AutocompleteDropdown };
