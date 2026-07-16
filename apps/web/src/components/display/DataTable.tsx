import { ReactNode } from 'react';

type Row = Record<string, string | number | ReactNode>;

type DataTableProps = {
  columns: string[];
  rows: Row[];
  minWidth?: number;
  nowrapColumns?: string[];
};

export function DataTable({ columns, rows, minWidth = 760, nowrapColumns = [] }: DataTableProps) {
  const nowrapColumnSet = new Set(nowrapColumns);

  return (
    <div className="overflow-x-auto rounded-md border border-commerce-line bg-white">
      <table className="w-full border-collapse bg-white text-left text-xs" style={{ minWidth }}>
        <thead className="bg-slate-50 text-slate-600">
          <tr>
            {columns.map((column) => (
              <th
                key={column}
                className={`border-b border-commerce-line px-3 py-3 font-black uppercase tracking-wide ${nowrapColumnSet.has(column) ? 'whitespace-nowrap' : ''}`}
              >
                {column}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row, index) => (
            <tr key={index} className="border-b border-slate-100 last:border-0 hover:bg-slate-50/80">
              {columns.map((column) => (
                <td
                  key={column}
                  className={`px-3 py-3 align-middle text-slate-700 ${nowrapColumnSet.has(column) ? 'whitespace-nowrap' : ''}`}
                >
                  {row[column]}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
