import { cn } from '@/lib/utils'

export function Tabs<T extends string>({
  value,
  onChange,
  items,
  label,
}: {
  value: T
  onChange: (value: T) => void
  items: { value: T; label: string }[]
  label: string
}) {
  return (
    <div role="tablist" aria-label={label} className="flex gap-4 border-b border-border">
      {items.map((item) => (
        <button
          key={item.value}
          type="button"
          role="tab"
          aria-selected={value === item.value}
          onClick={() => onChange(item.value)}
          className={cn(
            '-mb-px border-b-2 px-0.5 pb-2 text-body transition-colors',
            value === item.value
              ? 'border-foreground font-medium text-foreground'
              : 'border-transparent text-muted-foreground hover:text-foreground',
          )}
        >
          {item.label}
        </button>
      ))}
    </div>
  )
}
