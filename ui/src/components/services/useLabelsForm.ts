import { useCallback, useState } from "react";
import { FormInstance } from "antd";
import { ContextSystem, IntegrationSystem } from "../../api/apiTypes";

export function useLabelsForm(form: FormInstance | null) {
  const [technicalLabels, setTechnicalLabels] = useState<string[]>([]);
  const [userLabels, setUserLabels] = useState<string[]>([]);

  const onSetLabelsAndForm = useCallback(
    (data: ContextSystem | IntegrationSystem) => {
      setTechnicalLabels(
        data.labels?.filter((l) => l.technical).map((l) => l.name) || [],
      );
      setUserLabels(
        data.labels?.filter((l) => !l.technical).map((l) => l.name) || [],
      );

      if (form) {
        form.setFieldsValue({
          name: data.name,
          description: data.description,
          labels: [
            ...(data.labels?.filter((l) => l.technical).map((l) => l.name) ||
              []),
            ...(data.labels?.filter((l) => !l.technical).map((l) => l.name) ||
              []),
          ],
        });
      }
    },
    [form],
  );

  return {
    technicalLabels,
    userLabels,
    setUserLabels,
    onSetLabelsAndForm,
  };
}
